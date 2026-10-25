package io.github.hhn756.voidairrace.addons.script.api;

import io.github.hhn756.voidairrace.addons.script.api.handle.MatchHandle;
import io.github.hhn756.voidairrace.addons.script.simplevalue.Position;
import io.github.hhn756.voidairrace.core.playerstatemanager.PlayerStateManager;
import io.github.hhn756.voidairrace.core.playerstatemanager.systems.StateSystem;
import io.github.hhn756.voidairrace.core.team.TeamRoster;
import io.github.hhn756.voidairrace.core.team.Teams;
import io.github.hhn756.voidairrace.infrastructure.util.lua.ApiArgs;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.LuaRuntimeException;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.Table;
import net.sandius.rembulan.impl.NonsuspendableFunctionException;
import net.sandius.rembulan.runtime.AbstractFunction1;
import net.sandius.rembulan.runtime.ExecutionContext;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Team;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * 玩家查询类别（全局表{@code player}）的 Lua API 入口：{@code position} /
 * {@code team_of} / {@code online} / {@code play_state}<br>
 * 玩家以 UUID 字符串表示（值语义）；除{@code online}外，玩家不在线即抛错。
 * 本类别无状态，函数不依赖包实例
 * <p>
 * 本类是类别入口：装配期由{@code PluginApi}调用{@link #installInto}注入包环境
 * */
public final class PlayerApi {
    /** 无状态函数对象：不持有包、句柄或状态上下文，跨包共享同一实例 */
    private static final TeamOf TEAM_OF = new TeamOf();
    private static final Online ONLINE = new Online();
    private static final PlayStateQuery PLAY_STATE = new PlayStateQuery();

    /** 静态工具类，不可实例化 */
    private PlayerApi() {}

    /**
     * 向包脚本环境（{@code _G}）注入全局表{@code player}
     *
     * @param stateContext 共享 Lua 状态上下文（建表用）
     * @param env          包脚本环境（{@code _G}），全局表装入目标
     * */
    public static void installInto(@NonNull StateContext stateContext, @NonNull Table env) {
        Table player = stateContext.newTable();
        player.rawset(ByteString.of("position"), new PositionQuery(stateContext));
        player.rawset(ByteString.of("team_of"), TEAM_OF);
        player.rawset(ByteString.of("online"), ONLINE);
        player.rawset(ByteString.of("play_state"), PLAY_STATE);
        env.rawset(ByteString.of("player"), player);
    }

    /**
     * {@code player.position(uuid)}：查询玩家位置<br>
     * 返回坐标表 {@code {x=, y=, z=}}（浮点，所在世界坐标）
     * */
    private static final class PositionQuery extends AbstractFunction1 {
        /** 共享 Lua 状态上下文（返回坐标表时建表用） */
        private final @NonNull StateContext stateContext;

        PositionQuery(@NonNull StateContext stateContext) {
            this.stateContext = stateContext;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            Player player = MatchHandle.findPlayer(arg1, "player.position：第 1 个参数（玩家 UUID）");
            Location loc = player.getLocation();
            context.getReturnBuffer().setTo(Position.toLua(
                    stateContext, new Position(loc.x(), loc.y(), loc.z())));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code player.team_of(uuid)}：查询玩家所属队伍<br>
     * 返回队伍的数字 Id（1 起始，与内置队伍顺序一致）；玩家未入队时无返回值（nil）
     * */
    private static final class TeamOf extends AbstractFunction1 {
        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            Player player = MatchHandle.findPlayer(arg1, "player.team_of：第 1 个参数（玩家 UUID）");
            Team team = TeamRoster.getInstance().getTeam(player);
            if (team == null) {
                context.getReturnBuffer().setTo();
                return;
            }
            Teams teams = TeamRoster.getInstance().teamToEnum(team);
            if (teams == null) {
                context.getReturnBuffer().setTo();
                return;
            }
            context.getReturnBuffer().setTo((long) (teams.ordinal() + 1));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code player.online(uuid)}：查询玩家是否在线<br>
     * 返回布尔值；UUID 本身非法时抛错（与是否在线无关），离线玩家不抛错
     * */
    private static final class Online extends AbstractFunction1 {
        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            String uuidStr = ApiArgs.expectString(arg1, "player.online：第 1 个参数（玩家 UUID）");
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                throw new LuaRuntimeException("player.online：第 1 个参数（玩家 UUID）不是合法的 UUID：" + uuidStr);
            }
            context.getReturnBuffer().setTo(Boolean.valueOf(Bukkit.getPlayer(uuid) != null));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code player.play_state(uuid)}：查询玩家在{@code play}状态体系下的状态<br>
     * 返回状态 Id 的键名（{@code "free"} / {@code "playing"}）；状态未初始化时无返回值（nil）
     * */
    private static final class PlayStateQuery extends AbstractFunction1 {
        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            Player player = MatchHandle.findPlayer(arg1, "player.play_state：第 1 个参数（玩家 UUID）");
            NamespacedKey state = PlayerStateManager.getInstance().getState(player, StateSystem.PLAY.getName());
            if (state == null) {
                context.getReturnBuffer().setTo();
                return;
            }
            context.getReturnBuffer().setTo(ByteString.of(state.getKey()));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }
}
