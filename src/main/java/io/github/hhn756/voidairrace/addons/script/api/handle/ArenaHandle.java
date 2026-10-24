package io.github.hhn756.voidairrace.addons.script.api.handle;

import io.github.hhn756.voidairrace.addons.script.PackageAccess;
import io.github.hhn756.voidairrace.addons.script.api.ArenaApi;
import io.github.hhn756.voidairrace.addons.usrpackage.UsrPackage;
import io.github.hhn756.voidairrace.infrastructure.util.lua.ApiArgs;
import io.github.hhn756.voidairrace.result.OperationResult;
import io.github.hhn756.voidairrace.result.ValueResult;
import io.github.hhn756.voidairrace.service.arena.ArenaManager;
import io.github.hhn756.voidairrace.service.arena.ArenaToken;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.LuaRuntimeException;
import net.sandius.rembulan.Table;
import net.sandius.rembulan.impl.NonsuspendableFunctionException;
import net.sandius.rembulan.runtime.*;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * 竞技场句柄：一次竞技场借用（{@code arena.borrow()} 的返回值）在脚本侧的表示<br>
 * 每次借用产出全新实例（竞技场借据本身排他，无缓存语义），把借用协议包装为
 * {@code load} / {@code release} / {@code teleport} / {@code clear_chest} 四个函数——
 * 脚本报着的借据由脚本负责归还；包卸载时仍未归还的竞技场由
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
    private static final java.util.WeakHashMap<@NonNull Table, @NonNull ArenaHandle> BY_TABLE = new java.util.WeakHashMap<>();

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
        this.table.rawset(ByteString.of("teleport"), new Teleport(this));
        this.table.rawset(ByteString.of("clear_chest"), new ClearChest(this));
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
    private @NonNull World requireWorld() {
        ValueResult<World> result = ArenaManager.getInstance().getTokenWorld(requireToken());
        if (!(result instanceof ValueResult.WithValue(var world))) {
            throw new LuaRuntimeException("获取竞技场世界失败：" + result.techMessage());
        }
        return world;
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
            io.github.hhn756.voidairrace.VoidAirRace.getInstance().getLogger().warning(
                    "包清扫时归还竞技场未成功：" + result.techMessage());
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
     * {@code arena.teleport(uuid, x, y, z)}：把玩家传送到本竞技场世界内的指定坐标<br>
     * {@code uuid} 为玩家 UUID 字符串；坐标为竞技场世界内的世界坐标
     * */
    private static final class Teleport extends AbstractFunction4 {
        private final @NonNull ArenaHandle handle;

        Teleport(@NonNull ArenaHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1, @Nullable Object arg2,
                           @Nullable Object arg3, @Nullable Object arg4) {
            World world = handle.requireWorld();
            Player player = MatchHandle.findPlayer(arg1, "arena.teleport：第 1 个参数（玩家 UUID）");
            double x = ApiArgs.expectDouble(arg2, "arena.teleport：第 2 个参数（x）");
            double y = ApiArgs.expectDouble(arg3, "arena.teleport：第 3 个参数（y）");
            double z = ApiArgs.expectDouble(arg4, "arena.teleport：第 4 个参数（z）");

            player.teleport(new Location(world, x, y, z));
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code arena.clear_chest(x, y, z)}：清空本竞技场世界内指定坐标的容器<br>
     * 坐标须为箱子方块；战利品表设置不在本期开放（容器仅支持清空）
     * */
    private static final class ClearChest extends AbstractFunction3 {
        private final @NonNull ArenaHandle handle;

        ClearChest(@NonNull ArenaHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1,
                           @Nullable Object arg2, @Nullable Object arg3) {
            World world = handle.requireWorld();
            int x = (int) ApiArgs.expectDouble(arg1, "arena.clear_chest：第 1 个参数（x）");
            int y = (int) ApiArgs.expectDouble(arg2, "arena.clear_chest：第 2 个参数（y）");
            int z = (int) ApiArgs.expectDouble(arg3, "arena.clear_chest：第 3 个参数（z）");

            if (!(world.getBlockAt(x, y, z).getState() instanceof Chest chest)) {
                throw new LuaRuntimeException("该位置不是箱子：" + x + ", " + y + ", " + z);
            }
            chest.getInventory().clear(); // 清空箱子，避免残留
            chest.update();
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }
}
