package io.github.hhn756.voidairrace.addons.script.api.handle;

import io.github.hhn756.voidairrace.addons.script.MutationAccess;
import io.github.hhn756.voidairrace.addons.usrpackage.UsrPackage;
import io.github.hhn756.voidairrace.constants.Categories;
import io.github.hhn756.voidairrace.constants.Plugin;
import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.core.match.MatchCoordinator;
import io.github.hhn756.voidairrace.core.match.MatchState;
import io.github.hhn756.voidairrace.core.match.component.contestant.ContestantComp;
import io.github.hhn756.voidairrace.core.match.component.gametime.GameTimeComp;
import io.github.hhn756.voidairrace.core.match.component.scope.AreaTags;
import io.github.hhn756.voidairrace.core.match.component.scope.MatchArea;
import io.github.hhn756.voidairrace.core.match.component.scope.ScopeComp;
import io.github.hhn756.voidairrace.core.matchrule.RuleComp;
import io.github.hhn756.voidairrace.core.playerstatemanager.PlayerStateManager;
import io.github.hhn756.voidairrace.core.playerstatemanager.systems.play.PlayState;
import io.github.hhn756.voidairrace.core.team.TeamRoster;
import io.github.hhn756.voidairrace.core.team.Teams;
import io.github.hhn756.voidairrace.infrastructure.registry.Registry;
import io.github.hhn756.voidairrace.infrastructure.util.lua.ApiArgs;
import io.github.hhn756.voidairrace.result.OperationResult;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.LuaRuntimeException;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.Table;
import net.sandius.rembulan.impl.NonsuspendableFunctionException;
import net.sandius.rembulan.runtime.AbstractFunction0;
import net.sandius.rembulan.runtime.AbstractFunction1;
import net.sandius.rembulan.runtime.AbstractFunction2;
import net.sandius.rembulan.runtime.ExecutionContext;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Team;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.UUID;

/**
 * 比赛句柄：一场比赛在脚本侧的薄门面，包装**本质上需要比赛目标**的操作<br>
 * 无状态：每回调现造（{@link HandleApi#forMatch}），零缓存、零清理——生命周期
 * 全局化后句柄不再自管（无监听器、无借据账本），清扫职责全部落在全局 API 层
 * （包生死）与 ArenaManager（widen 守卫）
 * <p>
 * 留在句柄上的操作均以「对哪场比赛生效」为语义的一部分：枚举/淘汰/加入参赛者、
 * 启用比赛规则、登记比赛区域、查比赛状态与存活/排行情况、结束比赛、读写比赛时间。
 * 比赛结束后调用即抛 Lua 错误（对已卸载组件操作属未定义行为，必须抛错）；
 * 收尾回调（如地图 {@code on_over}）经{@link HandleApi#forEndingMatch}构造，
 * 只放行查询类函数。其余操作见包环境的插件全局表
 * （{@code server.broadcast / player.position / player.team_of / arena.borrow /
 * event.on / event.off}）
 * */
public final class MatchHandle {
    /** 所属比赛（薄门面无分离语义：比赛结束即整个句柄失去意义，每次回调现造） */
    private final @NonNull Match match;
    /** 共享 Lua 状态上下文（构建返回给脚本的表值时用） */
    private final @NonNull StateContext stateContext;
    /** 句柄所属地图的发起包（规则命名空间白名单的裁定基准） */
    private final @NonNull UsrPackage callerPackage;
    /**
     * 是否放行收尾阶段（比赛状态为{@code ENDING}）的查询<br>
     * 仅地图 {@code on_over} 回调为{@code true}：此时比赛已进入结束流程，
     * 组件可能正在逐个卸载，只允许读取不依赖组件生命周期的状态
     * */
    private final boolean allowEnding;
    /** 本比赛的句柄表 */
    private final @NonNull Table table;

    private MatchHandle(
            @NonNull StateContext stateContext,
            @NonNull Match match,
            @NonNull UsrPackage callerPackage,
            boolean allowEnding
    ) {
        this.match = match;
        this.stateContext = stateContext;
        this.callerPackage = callerPackage;
        this.allowEnding = allowEnding;
        this.table = stateContext.newTable();
        this.table.rawset(ByteString.of("state"), new State(this));
        this.table.rawset(ByteString.of("contestants"), new Contestants(this));
        this.table.rawset(ByteString.of("survivors"), new Survivors(this));
        this.table.rawset(ByteString.of("eliminated"), new Eliminated(this));
        this.table.rawset(ByteString.of("surviving_teams"), new SurvivingTeams(this));
        this.table.rawset(ByteString.of("player_ranking"), new PlayerRanking(this));
        this.table.rawset(ByteString.of("team_ranking"), new TeamRanking(this));
        this.table.rawset(ByteString.of("join"), new Join(this));
        this.table.rawset(ByteString.of("leave"), new Leave(this));
        this.table.rawset(ByteString.of("stop"), new Stop(this));
        this.table.rawset(ByteString.of("remaining"), new Remaining(this));
        this.table.rawset(ByteString.of("set_remaining"), new SetRemaining(this));
        this.table.rawset(ByteString.of("time_flow"), new TimeFlow(this));
        this.table.rawset(ByteString.of("set_time_flow"), new SetTimeFlow(this));
        this.table.rawset(ByteString.of("widen"), new Widen(this));
        this.table.rawset(ByteString.of("enable_rule"), new EnableRule(this));
    }

    /**
     * 构建指定比赛的句柄表（每回调现造，无缓存）
     *
     * @param stateContext  共享 Lua 状态上下文
     * @param match         目标比赛
     * @param callerPackage 句柄所属地图的发起包
     * @param allowEnding   是否放行收尾阶段（{@code ENDING}）的查询
     *
     * @return 该比赛的句柄表
     * */
    public static @NonNull Table tableOf(
            @NonNull StateContext stateContext,
            @NonNull Match match,
            @NonNull UsrPackage callerPackage,
            boolean allowEnding
    ) {
        return new MatchHandle(stateContext, match, callerPackage, allowEnding).table;
    }

    /**
     * 取回所属比赛，比赛不在进行中（且未放行收尾阶段）即抛 Lua 错误<br>
     * 查询类函数的前置检查：只读取不依赖组件生命周期的状态
     * */
    private @NonNull Match requireMatch() {
        MatchState state = match.state();
        if (state != MatchState.IN_PROGRESS && !(allowEnding && state == MatchState.ENDING)) {
            throw new LuaRuntimeException("比赛已结束，比赛句柄不可用");
        }
        return match;
    }

    /**
     * 取回所属比赛，比赛必须处于进行中，否则抛 Lua 错误<br>
     * 操作类函数的前置检查：比赛进入结束流程后组件可能正在卸载，
     * 对组件的任何写操作都属于未定义行为
     * */
    private @NonNull Match requireActiveMatch() {
        if (match.state() != MatchState.IN_PROGRESS) {
            throw new LuaRuntimeException("比赛已结束，比赛句柄不可用");
        }
        return match;
    }

    /**
     * 校验参数为玩家 UUID 字符串并解析为在线玩家
     *
     * @param arg  参数原值
     * @param what 参数用途说明，用于错误消息
     *
     * @return 对应的在线玩家
     * */
    public static @NonNull Player findPlayer(@Nullable Object arg, @NonNull String what) {
        String uuidStr = ApiArgs.expectString(arg, what);
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            throw new LuaRuntimeException(what + "不是合法的 UUID：" + uuidStr);
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) throw new LuaRuntimeException(what + "对应的玩家不在线：" + uuidStr);
        return player;
    }

    /**
     * 把玩家集合构建为 UUID 字符串的 Lua 数组表
     *
     * @param stateContext 共享 Lua 状态上下文
     * @param players      玩家集合
     *
     * @return 键 1..n 连续的 Lua 表
     * */
    private static @NonNull Table uuidArray(@NonNull StateContext stateContext, @NonNull Collection<? extends Player> players) {
        Table result = stateContext.newTable();
        long index = 1;
        for (Player player : players) {
            result.rawset(index++, ByteString.of(player.getUniqueId().toString()));
        }
        return result;
    }

    /**
     * 校验参数为非负整数（Lua 数字不分整型/浮点型，整数值统一接受）
     *
     * @param arg  参数原值
     * @param what 参数用途说明，用于错误消息
     *
     * @return 对应的整数值
     * */
    private static long expectNonNegativeInt(@Nullable Object arg, @NonNull String what) {
        double value = ApiArgs.expectDouble(arg, what);
        if (value < 0 || value > Integer.MAX_VALUE || value != Math.rint(value)) {
            throw new LuaRuntimeException(what + "必须是非负整数");
        }
        return (long) value;
    }

    /**
     * 校验参数为 Lua 布尔值
     *
     * @param arg  参数原值
     * @param what 参数用途说明，用于错误消息
     *
     * @return 对应的布尔值
     * */
    private static boolean expectBoolean(@Nullable Object arg, @NonNull String what) {
        if (arg instanceof Boolean b) return b;
        throw new LuaRuntimeException(what + "必须是布尔值");
    }

    // ------ 句柄函数 ------

    /**
     * {@code handle.state()}：获取比赛当前状态名<br>
     * 返回状态名字符串（如 {@code "IN_PROGRESS"}），与比赛状态枚举一一对应
     * */
    private static final class State extends AbstractFunction0 {
        private final @NonNull MatchHandle handle;

        State(@NonNull MatchHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            context.getReturnBuffer().setTo(ByteString.of(handle.match.state().name()));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code handle.contestants()}：枚举本场比赛的初始参赛者<br>
     * 返回 UUID 字符串数组（Lua 1 起始索引）；与参赛者组件的初始参赛者配置一致，
     * 比赛开始时在场即为初始参赛者
     * */
    private static final class Contestants extends AbstractFunction0 {
        private final @NonNull MatchHandle handle;

        Contestants(@NonNull MatchHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            Match match = handle.requireMatch();
            var config = match.configOf(ContestantComp.CONFIG_KEY);
            if (config == null) {
                // 参赛者组件的配置由配置实例化时自动创建，缺失属于实现错误
                throw new LuaRuntimeException("参赛者配置不存在（实现错误）");
            }
            Table result = handle.stateContext.newTable();
            long index = 1;
            for (Player player : config.initialContestants()) {
                result.rawset(index++, ByteString.of(player.getUniqueId().toString()));
            }
            context.getReturnBuffer().setTo(result);
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code handle.survivors()}：枚举当前仍存活的参赛者<br>
     * 返回 UUID 字符串数组（Lua 1 起始索引）；收尾回调中可读
     * */
    private static final class Survivors extends AbstractFunction0 {
        private final @NonNull MatchHandle handle;

        Survivors(@NonNull MatchHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            Match match = handle.requireMatch();
            context.getReturnBuffer().setTo(
                    uuidArray(handle.stateContext, match.comp(ContestantComp.class).survivingPlayers()));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code handle.eliminated()}：枚举已淘汰的参赛者<br>
     * 返回 UUID 字符串数组（Lua 1 起始索引）；收尾回调中可读
     * */
    private static final class Eliminated extends AbstractFunction0 {
        private final @NonNull MatchHandle handle;

        Eliminated(@NonNull MatchHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            Match match = handle.requireMatch();
            context.getReturnBuffer().setTo(
                    uuidArray(handle.stateContext, match.comp(ContestantComp.class).eliminatedPlayers()));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code handle.surviving_teams()}：枚举仍有存活参赛者的队伍<br>
     * 返回队伍数字 Id 数组（1 起始）；无法映射到内置队伍顺序的队伍被跳过
     * */
    private static final class SurvivingTeams extends AbstractFunction0 {
        private final @NonNull MatchHandle handle;

        SurvivingTeams(@NonNull MatchHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            Match match = handle.requireMatch();
            Table result = handle.stateContext.newTable();
            long index = 1;
            for (Team team : match.comp(ContestantComp.class).survivingTeams().keySet()) {
                Teams teams = TeamRoster.getInstance().teamToEnum(team);
                // 值显式转为 Object，规避 rawset(long, Object) 与 rawset(Object, Object) 的重载歧义
                if (teams != null) result.rawset(index++, (Object) Long.valueOf(teams.ordinal() + 1));
            }
            context.getReturnBuffer().setTo(result);
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code handle.player_ranking()}：获取玩家排行<br>
     * 返回 UUID 字符串数组（Lua 1 起始索引，第 1 项为第一名）：先退赛者在前，
     * 未退赛的存活者按加入顺序排在末尾；收尾回调中可读
     * */
    private static final class PlayerRanking extends AbstractFunction0 {
        private final @NonNull MatchHandle handle;

        PlayerRanking(@NonNull MatchHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            Match match = handle.requireMatch();
            context.getReturnBuffer().setTo(
                    uuidArray(handle.stateContext, match.comp(ContestantComp.class).playerRanking()));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code handle.team_ranking()}：获取队伍排行<br>
     * 返回队伍数字 Id 数组（Lua 1 起始索引，第 1 项为第一名）：按队伍首个成员退赛的先后，
     * 仅有存活成员且无人退赛的队伍排在末尾；无法映射到内置队伍顺序的队伍被跳过
     * */
    private static final class TeamRanking extends AbstractFunction0 {
        private final @NonNull MatchHandle handle;

        TeamRanking(@NonNull MatchHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            Match match = handle.requireMatch();
            Table result = handle.stateContext.newTable();
            long index = 1;
            for (Team team : match.comp(ContestantComp.class).teamRanking()) {
                Teams teams = TeamRoster.getInstance().teamToEnum(team);
                // 值显式转为 Object，规避 rawset(long, Object) 与 rawset(Object, Object) 的重载歧义
                if (teams != null) result.rawset(index++, (Object) Long.valueOf(teams.ordinal() + 1));
            }
            context.getReturnBuffer().setTo(result);
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code handle.join(uuid)}：使指定玩家加入本场比赛<br>
     * 已在本场的玩家幂等无操作；已被淘汰的玩家不能重新加入（恶心复活）；
     * 玩家必须在线且处于空闲状态（不在任何比赛中），否则抛 Lua 错误
     * */
    private static final class Join extends AbstractFunction1 {
        private final @NonNull MatchHandle handle;

        Join(@NonNull MatchHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            Match match = handle.requireActiveMatch();
            Player player = findPlayer(arg1, "handle.join：第 1 个参数（玩家 UUID）");
            ContestantComp comp = match.comp(ContestantComp.class);

            // 幂等：已在本场视为成功
            if (comp.contains(player)) {
                context.getReturnBuffer().setTo();
                return;
            }
            String uuid = player.getUniqueId().toString();
            if (comp.eliminatedPlayers().contains(player)) {
                throw new LuaRuntimeException("玩家已被淘汰，不能重新加入比赛：" + uuid);
            }
            if (!PlayerStateManager.getInstance().onState(player, PlayState.FREE.getId())) {
                throw new LuaRuntimeException("玩家当前不在空闲状态，不能加入比赛：" + uuid);
            }
            comp.joinMatch(player);
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code handle.leave(uuid)}：使指定玩家退出比赛（淘汰）<br>
     * 玩家不在参赛集合中时静默无操作
     * */
    private static final class Leave extends AbstractFunction1 {
        private final @NonNull MatchHandle handle;

        Leave(@NonNull MatchHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            Match match = handle.requireActiveMatch();
            Player player = findPlayer(arg1, "handle.leave：第 1 个参数（玩家 UUID）");
            match.comp(ContestantComp.class).leaveMatch(player);
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code handle.stop()}：结束本场比赛<br>
     * 仅当句柄所属比赛正是协调器管理的当前比赛时可结束（防止误停他人比赛）；
     * 结束后本句柄立即失效。比赛收尾流程（组件卸载、地图 {@code on_over}、
     * {@code match_over} 事件）由引擎统一执行
     * */
    private static final class Stop extends AbstractFunction0 {
        private final @NonNull MatchHandle handle;

        Stop(@NonNull MatchHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            Match match = handle.requireActiveMatch();
            MatchCoordinator coordinator = MatchCoordinator.getInstance();
            if (coordinator.getCurrentMatch() != match) {
                throw new LuaRuntimeException("比赛句柄不属于当前比赛，拒绝结束");
            }
            OperationResult result = coordinator.stopMatch();
            if (!result.isSuccess()) {
                throw new LuaRuntimeException("结束比赛失败："
                        + (result.techMessage() != null ? result.techMessage() : "未知原因"));
            }
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code handle.remaining()}：查询剩余比赛时长<br>
     * 返回剩余 tick 数（非负整数）
     * */
    private static final class Remaining extends AbstractFunction0 {
        private final @NonNull MatchHandle handle;

        Remaining(@NonNull MatchHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            Match match = handle.requireActiveMatch();
            context.getReturnBuffer().setTo((long) match.comp(GameTimeComp.class).getRemaining());
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code handle.set_remaining(n)}：设置剩余比赛时长<br>
     * {@code n} 为 tick 数，必须是非负整数；设置后触发比赛状态变化通知
     * */
    private static final class SetRemaining extends AbstractFunction1 {
        private final @NonNull MatchHandle handle;

        SetRemaining(@NonNull MatchHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            Match match = handle.requireActiveMatch();
            int n = (int) expectNonNegativeInt(arg1, "handle.set_remaining：第 1 个参数（剩余 tick）");
            match.comp(GameTimeComp.class).setRemaining(n);
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code handle.time_flow()}：查询比赛时间是否随 tick 减少<br>
     * 返回布尔值
     * */
    private static final class TimeFlow extends AbstractFunction0 {
        private final @NonNull MatchHandle handle;

        TimeFlow(@NonNull MatchHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            Match match = handle.requireActiveMatch();
            context.getReturnBuffer().setTo(Boolean.valueOf(match.comp(GameTimeComp.class).isTimeFlow()));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code handle.set_time_flow(flag)}：设置比赛时间是否随 tick 减少<br>
     * {@code flag} 为布尔值；{@code true} 减少、{@code false} 暂停
     * */
    private static final class SetTimeFlow extends AbstractFunction1 {
        private final @NonNull MatchHandle handle;

        SetTimeFlow(@NonNull MatchHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            Match match = handle.requireActiveMatch();
            boolean flag = expectBoolean(arg1, "handle.set_time_flow：第 1 个参数（是否流动）");
            match.comp(GameTimeComp.class).setTimeFlow(flag);
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code handle.widen(arena, tag)}：把竞技场登记为本场比赛的区域<br>
     * {@code arena} 为竞技场句柄表（{@code arena.borrow} 的返回值），须有效且未归还；
     * {@code tag} 为区域标签名（当前可用 {@code "main"}）。登记后归还职责移交
     * 比赛结束流程，手动归还通道在 ArenaManager 层关闭
     * */
    private static final class Widen extends AbstractFunction2 {
        private final @NonNull MatchHandle handle;

        Widen(@NonNull MatchHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1, @Nullable Object arg2) {
            Match match = handle.requireActiveMatch();
            if (!(arg1 instanceof Table arenaTable)) {
                throw new LuaRuntimeException("handle.widen：第 1 个参数必须是竞技场句柄表");
            }
            ArenaHandle arena = ArenaHandle.byTable(arenaTable);
            if (arena == null) {
                throw new LuaRuntimeException("handle.widen：第 1 个参数不是有效的竞技场句柄");
            }
            String tag = ApiArgs.expectString(arg2, "handle.widen：第 2 个参数（区域标签）");
            AreaTags areaTag;
            try {
                areaTag = AreaTags.valueOf(tag.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new LuaRuntimeException("未知区域标签：\"" + tag + "\"（当前可用：\"main\"）");
            }

            var token = arena.requireToken();
            // 只能把本包借到的竞技场登记进比赛，防止挪用他包的竞技场
            MutationAccess.requireArena(handle.callerPackage, token);
            // 登记双写：比赛区域集合（归还职责）+ 管理器标记（手动归还通道关闭）
            io.github.hhn756.voidairrace.service.arena.ArenaManager.getInstance().markMatchArea(token);
            match.comp(ScopeComp.class).widen(new MatchArea(token, areaTag));
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code handle.enable_rule(spec)}：在比赛中启用指定规则<br>
     * {@code spec} 为规则 Id（裸键 = 自身包命名空间，或 {@code ns:key} 全名）；
     * 命名空间白名单与存在性校验同标签引用规则（自身包、插件内置、已声明依赖），
     * 校验失败或启用失败即抛 Lua 错误
     * */
    private static final class EnableRule extends AbstractFunction1 {
        private final @NonNull MatchHandle handle;

        EnableRule(@NonNull MatchHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            Match match = handle.requireActiveMatch();
            String spec = ApiArgs.expectString(arg1, "handle.enable_rule：第 1 个参数（规则 Id）");

            // 解析规则 Id：裸键 = 自身包命名空间（与元素注册的键名规则一致）
            NamespacedKey ruleId;
            int separator = spec.indexOf(':');
            try {
                ruleId = separator < 0
                        ? new NamespacedKey(handle.callerPackage.id(), spec)
                        : new NamespacedKey(spec.substring(0, separator), spec.substring(separator + 1));
            } catch (IllegalArgumentException e) {
                throw new LuaRuntimeException("规则 Id 非法：" + spec + "（" + e.getMessage() + "）");
            }

            // 命名空间白名单：自身包、插件内置、已声明依赖（同标签引用的校验思路）
            String ns = ruleId.getNamespace();
            boolean allowed = ns.equals(handle.callerPackage.id())
                    || ns.equals(Plugin.ns)
                    || handle.callerPackage.dependencies().contains(ns);
            if (!allowed) {
                throw new LuaRuntimeException("规则命名空间不在允许范围内（自身包、插件内置或已声明依赖）：" + spec);
            }

            // 存在性校验
            if (Registry.getInstance().category(Categories.RULE).get(ruleId) == null) {
                throw new LuaRuntimeException("规则未注册：" + ruleId);
            }

            var result = match.comp(RuleComp.class).enableRule(ruleId);
            if (!result.isSuccess()) {
                throw new LuaRuntimeException(
                        "启用规则失败：" + (result.techMessage() != null ? result.techMessage() : ruleId));
            }
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }
}
