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
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * 玩家实体句柄：某个在线玩家在脚本侧的引用，由 {@code arena.entity_by_uuid(uuid, "player")}
 * / {@code arena.find_entity(uuid, "player")} 取得<br>
 * 与 {@link EntityHandle} 不共根：玩家的解析范围是全局在线状态，与所在世界、区块无关；
 * 修改权与可执行操作也与非玩家实体不同。句柄只保存竞技场句柄与 UUID，不保存玩家实例
 * <p>
 * 句柄上的竞技场只用于借据生命周期（竞技场归还后句柄失效）与跨世界传送的权限校验；
 * 玩家不必站在该竞技场世界内
 * */
public final class PlayerHandle {
    /** 借据来源：句柄生命周期与传送权限校验都经它 */
    private final @NonNull ArenaHandle arena;

    /** 玩家 UUID */
    private final @NonNull UUID uuid;

    /** 本句柄唯一的 Lua 表 */
    private final @NonNull Table table;

    PlayerHandle(@NonNull ArenaHandle arena, @NonNull UUID uuid) {
        this.arena = arena;
        this.uuid = uuid;
        this.table = arena.stateContext().newTable();

        table.rawset(ByteString.of("uuid"), new Uuid(this));
        table.rawset(ByteString.of("type"), new Type(this));
        table.rawset(ByteString.of("exists"), new Exists(this));
        table.rawset(ByteString.of("pos"), new Pos(this));
        table.rawset(ByteString.of("rotation"), new RotationOf(this));
        table.rawset(ByteString.of("teleport"), new Teleport(this));
        table.rawset(ByteString.of("teleport_to"), new TeleportTo(this));
    }

    /**
     * @return 本句柄唯一的 Lua 表
     * */
    public @NonNull Table table() {
        return table;
    }

    /**
     * 取回在线玩家：竞技场已归还或玩家不在线即抛 Lua 错误
     * */
    private @NonNull Player requireOnline() {
        arena.requireToken();
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            throw new LuaRuntimeException("玩家不在线：" + uuid);
        }
        return player;
    }

    /** 共享 Lua 状态上下文（建返回表用） */
    private @NonNull StateContext stateContext() {
        return arena.stateContext();
    }

    /**
     * 校验玩家传送的修改权与来源世界：目标玩家须属于调用方有修改权的比赛；
     * 玩家当前所在的竞技场世界须由调用方借入
     * */
    private void requireTeleportTarget(@NonNull Player player) {
        MutationAccess.requirePlayer(arena.pkg(), player);
        MutationAccess.requireArenaWorld(arena.pkg(), player.getWorld());
    }

    /**
     * {@code handle.uuid()}：返回玩家 UUID 字符串；不访问世界
     * */
    private static final class Uuid extends AbstractFunction0 {
        private final @NonNull PlayerHandle handle;

        Uuid(@NonNull PlayerHandle handle) {
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
     * {@code handle.type()}：固定返回 {@code "minecraft:player"}
     * */
    private static final class Type extends AbstractFunction0 {
        private final @NonNull PlayerHandle handle;

        Type(@NonNull PlayerHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            context.getReturnBuffer().setTo(
                    ByteString.of(EntityType.PLAYER.getKey().toString()));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code handle.exists()}：返回玩家是否在线；玩家离线不抛错
     * */
    private static final class Exists extends AbstractFunction0 {
        private final @NonNull PlayerHandle handle;

        Exists(@NonNull PlayerHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            handle.arena.requireToken();
            context.getReturnBuffer().setTo(Bukkit.getPlayer(handle.uuid) != null);
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code handle.pos()}：返回玩家当前浮点坐标表；玩家离线即抛错
     * */
    private static final class Pos extends AbstractFunction0 {
        private final @NonNull PlayerHandle handle;

        Pos(@NonNull PlayerHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            Location location = handle.requireOnline().getLocation();
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
     * {@code handle.rotation()}：返回玩家当前朝向表；玩家离线即抛错
     * */
    private static final class RotationOf extends AbstractFunction0 {
        private final @NonNull PlayerHandle handle;

        RotationOf(@NonNull PlayerHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            Player player = handle.requireOnline();
            context.getReturnBuffer().setTo(Rotation.toLua(
                    handle.stateContext(),
                    new Rotation(player.getYaw(), player.getPitch())));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code handle.teleport(pos[, rot])}：在玩家当前世界内传送；{@code rot} 省略时保留当前朝向
     * */
    private static final class Teleport extends AbstractFunctionAnyArg {
        private final @NonNull PlayerHandle handle;

        Teleport(@NonNull PlayerHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @NonNull Object[] args) {
            if (args.length < 1 || args.length > 2) {
                throw new LuaRuntimeException("handle.teleport：参数应为 (pos[, rot])");
            }
            Position pos = Position.expect(args[0], "handle.teleport：第 1 个参数（pos）");
            Rotation rot = args.length == 2
                    ? Rotation.expect(args[1], "handle.teleport：第 2 个参数（rot）")
                    : null;

            Player player = handle.requireOnline();
            handle.requireTeleportTarget(player);
            Location current = player.getLocation();
            Location target = new Location(
                    current.getWorld(), pos.x(), pos.y(), pos.z(),
                    rot == null ? current.getYaw() : rot.yaw(),
                    rot == null ? current.getPitch() : rot.pitch());
            if (!player.teleport(target)) {
                throw new LuaRuntimeException("传送玩家失败：" + handle.uuid);
            }
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code handle.teleport_to(arena, pos[, rot])}：把玩家传送到指定竞技场世界
     * */
    private static final class TeleportTo extends AbstractFunctionAnyArg {
        private final @NonNull PlayerHandle handle;

        TeleportTo(@NonNull PlayerHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @NonNull Object[] args) {
            if (args.length < 2 || args.length > 3) {
                throw new LuaRuntimeException("handle.teleport_to：参数应为 (arena, pos[, rot])");
            }
            ArenaHandle target = EntityHandle.requireArena(
                    args[0], "handle.teleport_to：第 1 个参数（arena）");
            Position pos = Position.expect(args[1], "handle.teleport_to：第 2 个参数（pos）");
            Rotation rot = args.length == 3
                    ? Rotation.expect(args[2], "handle.teleport_to：第 3 个参数（rot）")
                    : null;

            MutationAccess.requireArena(handle.arena.pkg(), target.requireToken());
            World targetWorld = target.requireWorld();
            Player player = handle.requireOnline();
            handle.requireTeleportTarget(player);

            EntityHandle.loadChunk(targetWorld, pos);
            Location current = player.getLocation();
            Location location = new Location(
                    targetWorld, pos.x(), pos.y(), pos.z(),
                    rot == null ? current.getYaw() : rot.yaw(),
                    rot == null ? current.getPitch() : rot.pitch());
            if (!player.teleport(location)) {
                throw new LuaRuntimeException("传送玩家到目标竞技场失败：" + handle.uuid);
            }
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }
}
