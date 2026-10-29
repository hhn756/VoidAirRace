package io.github.hhn756.voidairrace.addons.script.api.entity;

import io.github.hhn756.voidairrace.addons.script.MutationAccess;
import io.github.hhn756.voidairrace.addons.script.api.arena.ArenaHandle;
import io.github.hhn756.voidairrace.addons.script.simplevalue.Position;
import io.github.hhn756.voidairrace.addons.script.simplevalue.Rotation;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.LuaRuntimeException;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.Table;
import net.sandius.rembulan.impl.NonsuspendableFunctionException;
import net.sandius.rembulan.runtime.AbstractFunction0;
import net.sandius.rembulan.runtime.AbstractFunctionAnyArg;
import net.sandius.rembulan.runtime.ExecutionContext;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * 非玩家实体句柄根类：竞技场世界内某个实体在脚本侧的基础表示，由
 * {@code arena.entity_by_uuid(uuid, type)} / {@code arena.find_entity(uuid, type)}
 * 取得<br>
 * 根类持有唯一的 Lua 表并安装全部通用函数；子类只在构造器里经 {@link #addFunction}
 * 往同一张表追加专属函数，不新增实例字段，也不自行建表
 * <p>
 * 句柄只保存竞技场句柄、UUID 与预期实体类型，不保存实体实例或坐标：坐标会变，
 * UUID 不会。取得时不查找实体；每次函数调用重新经借据解析世界、按 UUID 查找，
 * 找不到或类型与预期不符即抛 Lua 错误。{@code exists()} 是唯一不抛错的读取
 * <p>
 * 预期类型是脚本对那个实体的断言，不是查询结果：使用时要求当前实体的类型与预期
 * 类型完全相等。句柄类如何映射见 {@link EntityHandleResolver}。子类函数不得缓存
 * 实体状态，一律从 {@link #requireEntity} 重取
 * */
public class EntityHandle {
    /** 借据来源：借据有效性、世界解析、修改权校验都经它 */
    private final @NonNull ArenaHandle arena;

    /** 实体 UUID；句柄的身份，跨区块重载与跨世界传送不变 */
    private final @NonNull UUID uuid;

    /** 预期实体类型：取得时不比对，每次函数调用都据此复查 */
    private final @NonNull EntityType type;

    /** 本句柄唯一的 Lua 表：根类建好后由子类往里追加函数 */
    private final @NonNull Table table;

    EntityHandle(
            @NonNull ArenaHandle arena,
            @NonNull UUID uuid,
            @NonNull EntityType type
    ) {
        if (type == EntityType.PLAYER) {
            throw new IllegalArgumentException("玩家实体使用 PlayerHandle");
        }
        this.arena = arena;
        this.uuid = uuid;
        this.type = type;
        this.table = arena.stateContext().newTable();

        installCommon(this.table);
    }

    /**
     * 向根表追加一个 Lua 函数；同名键会被覆盖，子类构造器专用
     *
     * @param name     脚本侧函数名
     * @param function Rembulan 函数对象
     * */
    protected final void addFunction(@NonNull String name, @NonNull Object function) {
        table.rawset(ByteString.of(name), function);
    }

    /**
     * @return 本句柄唯一的 Lua 表
     * */
    public final @NonNull Table table() {
        return table;
    }

    /**
     * 重新解析本句柄对应的实体，并复查类型是否为预期类型
     *
     * @return 当前实体
     *
     * @throws LuaRuntimeException 竞技场借据失效、实体不存在（含区块未加载）或类型不符时抛出
     * */
    final @NonNull Entity requireEntity() {
        World world = arena.requireWorld();
        Entity entity = world.getEntity(uuid);
        if (entity == null || !entity.isValid()) {
            throw new LuaRuntimeException("实体不存在或所在区块未加载：" + uuid);
        }
        EntityType actual = entity.getType();
        if (actual != type) {
            throw new LuaRuntimeException("实体不是预期类型 " + type.getKey()
                    + "（当前为 " + actual.getKey() + "）");
        }
        return entity;
    }

    /**
     * 存在性检查：实体能否按 UUID 找到、类型是否匹配、是否存活
     *
     * @return 存在且类型匹配时为{@code true}；不抛错（借据失效除外）
     * */
    final boolean existsNow() {
        World world = arena.requireWorld();
        Entity entity = world.getEntity(uuid);
        return entity != null && entity.isValid() && entity.getType() == type;
    }

    /**
     * 解析实体并收窄为指定种类
     *
     * @param clazz 目标种类
     *
     * @return 当前实体
     *
     * @throws LuaRuntimeException 实体缺失、类型不符或不是目标种类时抛出
     * */
    final <T extends Entity> @NonNull T requireTyped(@NonNull Class<T> clazz) {
        Entity entity = requireEntity();
        if (!clazz.isInstance(entity)) {
            throw new LuaRuntimeException("实体不是预期的种类：" + clazz.getSimpleName());
        }
        return clazz.cast(entity);
    }

    /** 校验竞技场修改权：专属函数中的写操作调用前须经此检查 */
    final void requireMutation() {
        MutationAccess.requireArena(arena.pkg(), arena.requireToken());
    }

    /** 共享 Lua 状态上下文（建返回表用） */
    final @NonNull StateContext stateContext() {
        return arena.stateContext();
    }

    /**
     * 加载坐标所在区块，并返回加载前是否已加载
     *
     * @param world 目标世界
     * @param pos   坐标
     *
     * @return 调用前区块已加载时为{@code true}
     * */
    static boolean loadChunk(@NonNull World world, @NonNull Position pos) {
        int chunkX = ((int) Math.floor(pos.x())) >> 4;
        int chunkZ = ((int) Math.floor(pos.z())) >> 4;
        boolean loaded = world.isChunkLoaded(chunkX, chunkZ);
        if (!loaded) world.getChunkAt(chunkX, chunkZ);
        return loaded;
    }

    // ------ 传送 ------

    /**
     * 在本世界内传送：要求调用方是竞技场当前借入者
     *
     * @param pos 目标坐标
     * @param rot 目标朝向；{@code null} 时保留当前朝向
     * */
    final void teleportWithin(@NonNull Position pos, @Nullable Rotation rot) {
        requireMutation();
        Entity entity = requireEntity();
        Location current = entity.getLocation();
        Location target = new Location(
                entity.getWorld(), pos.x(), pos.y(), pos.z(),
                rot == null ? current.getYaw() : rot.yaw(),
                rot == null ? current.getPitch() : rot.pitch());
        if (!entity.teleport(target)) {
            throw new LuaRuntimeException("传送实体失败：" + uuid);
        }
    }

    /**
     * 传送到另一竞技场世界：要求调用方同时是来源与目标竞技场的当前借入者
     *
     * @param target 目标竞技场句柄
     * @param pos    目标坐标
     * @param rot    目标朝向；{@code null} 时保留当前朝向
     * */
    final void teleportTo(
            @NonNull ArenaHandle target,
            @NonNull Position pos,
            @Nullable Rotation rot
    ) {
        requireMutation();
        MutationAccess.requireArena(arena.pkg(), target.requireToken());
        World targetWorld = target.requireWorld();
        Entity entity = requireEntity();
        loadChunk(targetWorld, pos);
        Location current = entity.getLocation();
        Location location = new Location(
                targetWorld, pos.x(), pos.y(), pos.z(),
                rot == null ? current.getYaw() : rot.yaw(),
                rot == null ? current.getPitch() : rot.pitch());
        if (!entity.teleport(location)) {
            throw new LuaRuntimeException("传送实体到目标竞技场失败：" + uuid);
        }
    }

    // ------ 通用函数 ------

    /** 安装与实体种类无关的通用函数 */
    private void installCommon(@NonNull Table table) {
        table.rawset(ByteString.of("uuid"), new Uuid(this));
        table.rawset(ByteString.of("type"), new Type(this));
        table.rawset(ByteString.of("exists"), new Exists(this));
        table.rawset(ByteString.of("pos"), new Pos(this));
        table.rawset(ByteString.of("rotation"), new RotationOf(this));
        table.rawset(ByteString.of("teleport"), new Teleport(this));
        table.rawset(ByteString.of("teleport_to"), new TeleportTo(this));
        table.rawset(ByteString.of("remove"), new Remove(this));
    }

    /**
     * {@code entity.uuid()}：返回实体 UUID 字符串；不访问世界
     * */
    private static final class Uuid extends AbstractFunction0 {
        private final @NonNull EntityHandle handle;

        Uuid(@NonNull EntityHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            context.getReturnBuffer().setTo(ByteString.of(handle.uuid.toString()));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code entity.type()}：返回预期实体键字符串；不访问世界
     * */
    private static final class Type extends AbstractFunction0 {
        private final @NonNull EntityHandle handle;

        Type(@NonNull EntityHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            context.getReturnBuffer().setTo(ByteString.of(handle.type.getKey().toString()));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code entity.exists()}：实体当前能否按 UUID 找到且类型匹配；不抛错
     * */
    private static final class Exists extends AbstractFunction0 {
        private final @NonNull EntityHandle handle;

        Exists(@NonNull EntityHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            context.getReturnBuffer().setTo(handle.existsNow());
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code entity.pos()}：返回当前浮点坐标表 {@code {x=, y=, z=}}
     * */
    private static final class Pos extends AbstractFunction0 {
        private final @NonNull EntityHandle handle;

        Pos(@NonNull EntityHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            Location location = handle.requireEntity().getLocation();
            context.getReturnBuffer().setTo(Position.toLua(
                    handle.stateContext(),
                    new Position(location.getX(), location.getY(), location.getZ())));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code entity.rotation()}：返回当前朝向表 {@code {yaw=, pitch=}}
     * */
    private static final class RotationOf extends AbstractFunction0 {
        private final @NonNull EntityHandle handle;

        RotationOf(@NonNull EntityHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            Entity entity = handle.requireEntity();
            context.getReturnBuffer().setTo(Rotation.toLua(
                    handle.stateContext(),
                    new Rotation(entity.getYaw(), entity.getPitch())));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code entity.teleport(pos[, rot])}：在本世界内传送；{@code rot} 省略时保留当前朝向
     * */
    private static final class Teleport extends AbstractFunctionAnyArg {
        private final @NonNull EntityHandle handle;

        Teleport(@NonNull EntityHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @NonNull Object[] args) {
            if (args.length < 1 || args.length > 2) {
                throw new LuaRuntimeException("entity.teleport：参数应为 (pos[, rot])");
            }
            Position pos = Position.expect(args[0], "entity.teleport：第 1 个参数（pos）");
            Rotation rot = args.length == 2
                    ? Rotation.expect(args[1], "entity.teleport：第 2 个参数（rot）")
                    : null;
            handle.teleportWithin(pos, rot);
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code entity.teleport_to(arena, pos[, rot])}：传送到另一竞技场世界
     * */
    private static final class TeleportTo extends AbstractFunctionAnyArg {
        private final @NonNull EntityHandle handle;

        TeleportTo(@NonNull EntityHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @NonNull Object[] args) {
            if (args.length < 2 || args.length > 3) {
                throw new LuaRuntimeException("entity.teleport_to：参数应为 (arena, pos[, rot])");
            }
            ArenaHandle target = requireArena(args[0], "entity.teleport_to：第 1 个参数（arena）");
            Position pos = Position.expect(args[1], "entity.teleport_to：第 2 个参数（pos）");
            Rotation rot = args.length == 3
                    ? Rotation.expect(args[2], "entity.teleport_to：第 3 个参数（rot）")
                    : null;
            handle.teleportTo(target, pos, rot);
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code entity.remove()}：直接从世界移除，无掉落、无死亡事件
     * */
    private static final class Remove extends AbstractFunction0 {
        private final @NonNull EntityHandle handle;

        Remove(@NonNull EntityHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            handle.requireMutation();
            handle.requireEntity().remove();
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * 解析竞技场句柄参数
     *
     * @param arg  Lua 原值
     * @param what 参数用途说明，用于错误消息
     * */
    static @NonNull ArenaHandle requireArena(@Nullable Object arg, @NonNull String what) {
        if (!(arg instanceof Table table)) {
            throw new LuaRuntimeException(what + "必须是竞技场句柄表");
        }
        ArenaHandle arena = ArenaHandle.byTable(table);
        if (arena == null) {
            throw new LuaRuntimeException(what + "不是竞技场句柄表");
        }
        return arena;
    }
}
