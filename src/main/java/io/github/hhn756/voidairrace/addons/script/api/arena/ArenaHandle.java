package io.github.hhn756.voidairrace.addons.script.api.arena;

import io.github.hhn756.voidairrace.Voidairrace;
import io.github.hhn756.voidairrace.addons.script.MutationAccess;
import io.github.hhn756.voidairrace.addons.script.PackageAccess;
import io.github.hhn756.voidairrace.addons.script.api.block.BlockHandleResolver;
import io.github.hhn756.voidairrace.addons.script.api.block.BlockPlacer;
import io.github.hhn756.voidairrace.addons.script.api.entity.EntityArgs;
import io.github.hhn756.voidairrace.addons.script.api.entity.EntityHandleResolver;
import io.github.hhn756.voidairrace.addons.script.api.entity.EntitySpawner;
import io.github.hhn756.voidairrace.addons.script.simplevalue.*;
import io.github.hhn756.voidairrace.addons.usrpackage.UsrPackage;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.infrastructure.config.Config;
import io.github.hhn756.voidairrace.infrastructure.config.file.LuaConfigKey;
import io.github.hhn756.voidairrace.infrastructure.config.file.PublicFile;
import io.github.hhn756.voidairrace.infrastructure.language.ServerLanguage;
import io.github.hhn756.voidairrace.infrastructure.util.lua.ApiArgs;
import io.github.hhn756.voidairrace.result.OperationResult;
import io.github.hhn756.voidairrace.result.ValueResult;
import io.github.hhn756.voidairrace.service.arena.ArenaManager;
import io.github.hhn756.voidairrace.service.arena.ArenaToken;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.LuaRuntimeException;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.Table;
import net.sandius.rembulan.impl.NonsuspendableFunctionException;
import net.sandius.rembulan.runtime.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * 竞技场句柄：一次竞技场借用（{@code arena.borrow()} 的返回值）在脚本侧的表示<br>
 * 每次借用产出全新实例（竞技场借据本身排他，无缓存语义），把借用协议包装为
 * {@code load} / {@code release} / {@code entity_by_uuid} / {@code find_entity} /
 * {@code spawn} / {@code entity_count} /
 * {@code get_block_type} / {@code get_block} / {@code set} / {@code fill}
 * 十个函数——脚本报着的借据由脚本负责归还；包卸载时仍未归还的竞技场由
 * {@link ArenaApi}
 * 的清扫统一归还
 * <p>
 * 登记为比赛区域（比赛句柄的 {@code widen}）后手动归还通道在 ArenaManager 层关闭：
 * 归还职责已移交比赛结束流程（组件卸载时统一归还），双通道归还只会产生噪音——
 * 防线在管理器状态上，不管归还请求来自哪条路径都被拒绝
 * <p>
 * 本类无生命周期监听：借用寿命 ≤ 所属包寿命，包清扫即本句柄的终点；
 * 句柄失效条件唯一为借据归还（显式 release 或包清扫），失效后所有函数抛 Lua 错误
 * */
public final class ArenaHandle {
    /**
     * 句柄表 → 句柄实例的反查（弱键：Lua 侧引用消失后条目随之回收）<br>
     * 比赛句柄 widen 时凭脚本传来的表反查句柄实例；显式归还与包清扫时移除
     */
    private static final WeakHashMap<@NonNull Table, @NonNull ArenaHandle> BY_TABLE = new WeakHashMap<>();

    /** 借据所属包（竞技场模板路径解析基准） */
    private final @NonNull UsrPackage pkg;

    /** 借入者（所属包的竞技场类别实例）：借据账本的登记与注销由本句柄自动维护 */
    private final @NonNull ArenaApi owner;

    /** 包 Id → 已加载包对象的解析函数（跨包模板路径解析用） */
    private final @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver;

    /** 包内的竞技场借据 */
    private final @NonNull ArenaToken token;

    /** 本借用的句柄表 */
    private final @NonNull Table table;

    /** 已归还（显式 release 或包清扫） */
    private boolean returned = false;

    public ArenaHandle(
            @NonNull ArenaApi owner,
            @NonNull ArenaToken token
    ) {
        this.owner = owner;
        this.pkg = owner.pkg();
        this.packageResolver = owner.packageResolver();
        this.token = token;
        this.table = owner.stateContext().newTable();
        this.table.rawset(ByteString.of("load"), new Load(this));
        this.table.rawset(ByteString.of("release"), new Release(this));
        this.table.rawset(ByteString.of("entity_by_uuid"), new EntityByUuid(this));
        this.table.rawset(ByteString.of("find_entity"), new FindEntity(this));
        this.table.rawset(ByteString.of("spawn"), new Spawn(this));
        this.table.rawset(ByteString.of("entity_count"), new EntityCount(this));
        this.table.rawset(ByteString.of("get_block_type"), new GetBlockType(this));
        this.table.rawset(ByteString.of("get_block"), new GetBlock(this));
        this.table.rawset(ByteString.of("set"), new Set(this));
        this.table.rawset(ByteString.of("fill"), new Fill(this));
        BY_TABLE.put(this.table, this);
        owner.trackArena(this);
    }

    /**
     * 按句柄表反查句柄实例
     *
     * @param arenaTable 脚本侧的竞技场句柄表
     *
     * @return 对应的句柄实例；不是任何有效借用的句柄表时返回{@code null}
     * */
    public static @Nullable ArenaHandle byTable(@NonNull Table arenaTable) {
        return BY_TABLE.get(arenaTable);
    }

    /**
     * 获取本借用的句柄表
     *
     * @return 句柄表
     * */
    public @NonNull Table table() {
        return table;
    }

    /**
     * 取回有效借据：已归还即抛 Lua 错误<br>
     * 所有句柄函数与比赛句柄 widen 的前置检查；句柄失效条件唯一为借据归还
     * */
    public @NonNull ArenaToken requireToken() {
        if (returned) throw new LuaRuntimeException("竞技场已归还，竞技场句柄不可用");
        return token;
    }

    /**
     * 取回有效借据对应的已加载世界（未加载时自动加载）
     * */
    public @NonNull World requireWorld() {
        ValueResult<World> result = ArenaManager.getInstance().getTokenWorld(requireToken());
        if (!(result instanceof ValueResult.WithValue(var world))) {
            throw new LuaRuntimeException("获取竞技场世界失败：" + result.techMessage());
        }
        return world;
    }

    /** 借据所属包（修改权校验与建表用） */
    public @NonNull UsrPackage pkg() {
        return pkg;
    }

    /** 共享 Lua 状态上下文（方块句柄等从属对象建表用） */
    public @NonNull StateContext stateContext() {
        return owner.stateContext();
    }

    /**
     * 包清扫时的强制归还：未归还且未登记为比赛区域的借据在此归还<br>
     * 已登记为比赛区域的跳过——归还职责在比赛结束流程（组件卸载时统一归还）；
     * 已显式归还的跳过；归还失败只记日志（清扫阶段无法补救）
     * */
    public void forceReturn() {
        if (returned) return;
        OperationResult result = ArenaManager.getInstance().returnArena(token);
        if (result.isSuccess()) {
            returned = true;
            BY_TABLE.remove(table);
            owner.forgetArena(this);
        } else {
            // 已登记为比赛区域的借据走不到归还（被管理器拒绝），留给比赛结束流程；
            // 其余失败（借据失效等）记日志即可
            Voidairrace.getInstance().getLogger().warning(
                    ServerLanguage.translate(
                            TranslateKeys.Log.ARENA_HANDLE_CLEANUP_RETURN_FAILED, result.techMessage()));
        }
    }

    // ------ 句柄函数 ------

    /**
     * {@code arena.load(spec)}：将包内世界模板加载到本竞技场<br>
     * {@code spec} 为资源路径（{@code path} 本包或 {@code pkgid:path} 跨包，规则同资源访问），
     * 指向包内的世界模板目录；模板已加载时重新加载（不保存修改）
     * */
    private static final class Load extends AbstractFunction1 {
        private final @NonNull ArenaHandle handle;

        Load(@NonNull ArenaHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            ArenaToken token = handle.requireToken();
            MutationAccess.requireArena(handle.pkg, token);
            String spec = ApiArgs.expectString(arg1, "arena.load：第 1 个参数（模板路径）");
            PackageAccess.Target target = PackageAccess.resolve(handle.pkg, spec, handle.packageResolver);
            Path templateDir = target.pkg().resolve(target.relPath());
            if (!Files.isDirectory(templateDir)) {
                throw new LuaRuntimeException("竞技场模板目录不存在或不是目录：" + spec);
            }

            OperationResult result = ArenaManager.getInstance().loadArena(token, templateDir);
            if (!result.isSuccess()) {
                throw new LuaRuntimeException(
                        "加载竞技场模板失败：" + (result.techMessage() != null ? result.techMessage() : spec));
            }
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code arena.release()}：归还本竞技场（世界随之卸载且不保存，场内玩家被传送回出生点）<br>
     * 已登记为比赛区域的竞技场归还请求会被 ArenaManager 拒绝（比赛结束时自动归还）
     * */
    private static final class Release extends AbstractFunction0 {
        private final @NonNull ArenaHandle handle;

        Release(@NonNull ArenaHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            ArenaToken token = handle.requireToken();

            OperationResult result = ArenaManager.getInstance().returnArena(token);
            if (!result.isSuccess()) {
                throw new LuaRuntimeException("归还竞技场失败：" + result.techMessage());
            }
            handle.returned = true;
            BY_TABLE.remove(handle.table);
            handle.owner.forgetArena(handle);
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code arena.entity_by_uuid(uuid, type)}：取得本竞技场世界内指定实体的实体句柄表<br>
     * {@code uuid} 为 UUID 字符串；{@code type} 为预期实体类型（实体键），只决定句柄类与
     * 类型断言，取得时不查找实体、也不与实体实际类型比对。构造时校验借据有效、世界就绪；
     * 未加载的竞技场世界按管理器规则在此加载
     * */
    private static final class EntityByUuid extends AbstractFunction2 {
        private final @NonNull ArenaHandle handle;

        EntityByUuid(@NonNull ArenaHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1, @Nullable Object arg2) {
            UUID uuid = EntityArgs.expectUuid(
                    arg1, "arena.entity_by_uuid：第 1 个参数（uuid）");
            EntityType type = EntityTypeValue.expect(
                    arg2, "arena.entity_by_uuid：第 2 个参数（预期类型）");
            handle.requireWorld();
            context.getReturnBuffer().setTo(
                    EntityHandleResolver.create(handle, uuid, type));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code arena.find_entity(uuid, type)}：按同样参数创建实体句柄，但先做一次存在性检查<br>
     * 找到且类型匹配时返回句柄；找不到、已死亡或类型不符时返回 {@code nil}；
     * 参数错误照常抛错。玩家按在线状态判定，其余实体按本竞技场世界已加载区块判定
     * */
    private static final class FindEntity extends AbstractFunction2 {
        private final @NonNull ArenaHandle handle;

        FindEntity(@NonNull ArenaHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1, @Nullable Object arg2) {
            UUID uuid = EntityArgs.expectUuid(
                    arg1, "arena.find_entity：第 1 个参数（uuid）");
            EntityType type = EntityTypeValue.expect(
                    arg2, "arena.find_entity：第 2 个参数（预期类型）");
            World world = handle.requireWorld();

            boolean found;
            if (type == EntityType.PLAYER) {
                Player player = Bukkit.getPlayer(uuid);
                found = player != null;
            } else {
                Entity entity = world.getEntity(uuid);
                found = entity != null && entity.isValid() && entity.getType() == type;
            }
            if (!found) {
                context.getReturnBuffer().setTo();
                return;
            }
            context.getReturnBuffer().setTo(
                    EntityHandleResolver.create(handle, uuid, type));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code arena.spawn(pos, descriptor)}：在本竞技场世界内生成一个实体，返回新实体的 UUID<br>
     * {@code pos} 为浮点坐标表；描述符形状见 {@link EntityDescriptor}。权限、坐标边界、
     * 数量上限、区块与失败清理见 {@link EntitySpawner}
     * */
    private static final class Spawn extends AbstractFunction2 {
        private final @NonNull ArenaHandle handle;

        Spawn(@NonNull ArenaHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1, @Nullable Object arg2) {
            Position pos = Position.expect(arg1, "arena.spawn：第 1 个参数（pos）");
            EntityDescriptor descriptor = EntityDescriptor.expect(
                    arg2, "arena.spawn：第 2 个参数（实体描述符）");
            UUID uuid = EntitySpawner.spawn(handle, pos, descriptor);
            context.getReturnBuffer().setTo(ByteString.of(uuid.toString()));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code arena.entity_count()}：返回本竞技场世界当前的已加载实体数<br>
     * 口径与服务端 {@code World#getEntityCount()} 一致，未加载区块中的实体不计入
     * */
    private static final class EntityCount extends AbstractFunction0 {
        private final @NonNull ArenaHandle handle;

        EntityCount(@NonNull ArenaHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            World world = handle.requireWorld();
            context.getReturnBuffer().setTo((long) world.getEntityCount());
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code arena.get_block_type(pos)}：返回本竞技场世界内指定坐标方块的材质键<br>
     * {@code pos} 为坐标表，按向下取整落到方块网格（负数不会朝零截断）；空气返回
     * {@code "minecraft:air"}，不抛错。只想知道某格是什么方块时用本函数，不必先知道
     * 材质再走 {@link GetBlock} 取句柄
     * */
    private static final class GetBlockType extends AbstractFunction1 {
        private final @NonNull ArenaHandle handle;

        GetBlockType(@NonNull ArenaHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            BlockPos pos = BlockPos.expect(
                    arg1, "arena.get_block_type：第 1 个参数（pos）");
            World world = handle.requireWorld();
            Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
            context.getReturnBuffer().setTo(ByteString.of(block.getType().getKey().toString()));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code arena.get_block(pos, type)}：取得本竞技场世界内指定方块的方块句柄表<br>
     * {@code pos} 为坐标表，按向下取整落到方块网格（负数不会朝零截断）；
     * {@code type} 为预期类型：脚本给出的方块材质键（{@code "chest"} 或
     * {@code "minecraft:chest"}），表示脚本预期该格方块是此材质。预期类型只用于
     * 选择句柄类（见 {@link BlockHandleResolver}），不修改方块，取得时也不与方块
     * 实际材质比对——比对推迟到使用句柄时，见 {@link BlockHandle#requireBlock}
     * */
    private static final class GetBlock extends AbstractFunction2 {
        private final @NonNull ArenaHandle handle;

        GetBlock(@NonNull ArenaHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1, @Nullable Object arg2) {
            BlockPos pos = BlockPos.expect(
                    arg1, "arena.get_block：第 1 个参数（pos）");
            String expectedType = ApiArgs.expectString(
                    arg2, "arena.get_block：第 2 个参数（预期类型）");

            Material expectedMaterial = Material.matchMaterial(expectedType);
            if (expectedMaterial == null) {
                throw new LuaRuntimeException("arena.get_block：预期类型不是有效的方块材质：" + expectedType);
            }
            if (!expectedMaterial.isBlock()) {
                throw new LuaRuntimeException("arena.get_block：预期类型不是方块：" + expectedType);
            }

            World world = handle.requireWorld();
            Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
            context.getReturnBuffer().setTo(
                    BlockHandleResolver.create(handle, pos, expectedMaterial, block).table());
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code arena.set(pos, 方块描述符[, physics])}：把指定坐标的方块替换为描述符所述方块<br>
     * {@code pos} 为坐标表，按向下取整落到方块网格；{@code physics} 省略时为
     * {@code false}，可选此项控制是否触发方块更新；描述符形状见
     * {@link BlockDescriptor}
     * */
    private static final class Set extends AbstractFunctionAnyArg {
        private final @NonNull ArenaHandle handle;

        Set(@NonNull ArenaHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @NonNull Object[] args) {
            MutationAccess.requireArena(handle.pkg, handle.requireToken());
            if (args.length < 2 || args.length > 3) {
                throw new LuaRuntimeException("arena.set：参数应为 (pos, 方块描述符[, physics])");
            }
            BlockPos pos = BlockPos.expect(args[0], "arena.set：第 1 个参数（pos）");
            BlockDescriptor descriptor = BlockDescriptor.expect(
                    args[1], "arena.set：第 2 个参数（方块描述符）");
            boolean physics = args.length == 3
                    && ApiArgs.expectBoolean(args[2], "arena.set：第 3 个参数（physics）");

            World world = handle.requireWorld();
            BlockPlacer.place(
                    world.getBlockAt(pos.x(), pos.y(), pos.z()), descriptor, physics, "arena.set");
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code arena.fill(pos1, pos2, 方块描述符[, physics])}：把两个角点围成的长方体
     * 范围内每一格替换为描述符所述方块，实体数据逐格写入<br>
     * 两个角点无顺序要求；格数上限取 {@code lua_config.yml} 的
     * {@code max_fill_blocks}，超出时在任何写入前抛错；{@code physics} 语义同
     * {@link Set}
     * */
    private static final class Fill extends AbstractFunctionAnyArg {
        private final @NonNull ArenaHandle handle;

        Fill(@NonNull ArenaHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @NonNull Object[] args) {
            MutationAccess.requireArena(handle.pkg, handle.requireToken());
            if (args.length < 3 || args.length > 4) {
                throw new LuaRuntimeException(
                        "arena.fill：参数应为 (pos1, pos2, 方块描述符[, physics])");
            }
            BlockPos pos1 = BlockPos.expect(args[0], "arena.fill：第 1 个参数（pos1）");
            BlockPos pos2 = BlockPos.expect(args[1], "arena.fill：第 2 个参数（pos2）");
            BlockDescriptor descriptor = BlockDescriptor.expect(
                    args[2], "arena.fill：第 3 个参数（方块描述符）");
            boolean physics = args.length == 4
                    && ApiArgs.expectBoolean(args[3], "arena.fill：第 4 个参数（physics）");

            int minX = Math.min(pos1.x(), pos2.x());
            int minY = Math.min(pos1.y(), pos2.y());
            int minZ = Math.min(pos1.z(), pos2.z());
            int maxX = Math.max(pos1.x(), pos2.x());
            int maxY = Math.max(pos1.y(), pos2.y());
            int maxZ = Math.max(pos1.z(), pos2.z());
            long count = ((long) maxX - minX + 1)
                    * ((long) maxY - minY + 1)
                    * ((long) maxZ - minZ + 1);
            int limit = Config.getInstance()
                    .getYmlConfig(PublicFile.LUA)
                    .get(LuaConfigKey.MAX_FILL_BLOCKS, 32768);
            if (count > limit) {
                throw new LuaRuntimeException("arena.fill：范围包含 " + count + " 格，超过上限 "
                        + limit + "（lua_config.yml 的 max_fill_blocks）");
            }

            World world = handle.requireWorld();
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockPlacer.place(
                                world.getBlockAt(x, y, z), descriptor, physics, "arena.fill");
                    }
                }
            }
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }
}
