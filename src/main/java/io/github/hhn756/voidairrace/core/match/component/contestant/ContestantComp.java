package io.github.hhn756.voidairrace.core.match.component.contestant;

import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.match.ComponentPriority;
import io.github.hhn756.voidairrace.core.match.DataKey;
import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.core.match.componentbase.*;
import io.github.hhn756.voidairrace.core.team.TeamRoster;
import io.github.hhn756.voidairrace.event.PlayerJoinMatchEvent;
import io.github.hhn756.voidairrace.event.PlayerLeaveMatchEvent;
import io.github.hhn756.voidairrace.result.ValueResult;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.Range;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * 参赛者管理组件：跟踪比赛中存活的玩家及其队伍信息
 */
public class ContestantComp extends MatchComp
        implements StartableComp<ContestantComp.ContestantStartArg, ContestantComp.ContestantStartContext>,
        EndableComp<ContestantComp.ContestantEndArg, ContestantComp.ContestantEndContext>,
        ConfigurableComp<ContestantComp.ContestantECFG, ContestantComp.ContestantConfig> {

    // ==================== 组件内部状态 ====================

    /**
     * 存活的所有玩家<br>
     * 有序集合：迭代顺序为加入比赛的先后，结束上下文中的未退赛者排行依赖它保持确定性
     * */
    private final Set<Player> survivingPlayers = new LinkedHashSet<>();

    /**
     * 存活队伍列表<br>
     * 键为剩余（存活）玩家数大于0的队伍，值为对应队伍的剩余（存活）玩家数
     * */
    private final Map<Team, Integer> survivingTeams = new HashMap<>();

    /**
     * 已淘汰、不可加入比赛的所有玩家<br>
     * 有序集合：迭代顺序即退赛先后，玩家排行以此为准
     * */
    private final Set<Player> eliminatedPlayers = new LinkedHashSet<>();

    /**
     * 队伍退赛顺序，索引 0 为最先有成员退赛的队伍<br>
     * 有序集合：队伍在首个成员退赛时记入，后续成员退赛自然不重复
     * */
    private final Set<Team> teamLeaveOrder = new LinkedHashSet<>();

    /**
     * 玩家参赛时所属队伍的归属快照<br>
     * 退赛流程可能先切出到空闲状态（此时玩家已离开计分板队伍），
     * 因此队伍归属必须在参赛时记录，不能退赛时现查
     * */
    private final Map<Player, Team> teamAtJoin = new HashMap<>();
    private Match match;

    /**
     * 结束上下文的数据键
     * */
    public static final DataKey<ContestantEndContext> END_KEY =
            DataKey.of(ContestantComp.class, ContestantEndContext.class);

    // ==================== ConfigurableComp 实现 ====================

    public static final DataKey<ContestantConfig> CONFIG_KEY = DataKey.of(ContestantComp.class, ContestantConfig.class);

    @Override
    public DataKey<ContestantConfig> getConfigKey() {
        return CONFIG_KEY;
    }

    @Override
    public @NonNull ValueResult<ContestantConfig> createCustomConfig(@Nullable ContestantECFG expected) {
        Collection<? extends Player> initialPlayers = expected != null
                ? expected.expectedContestants()
                : Collections.emptyList();
        return ValueResult.success(new ContestantConfig(initialPlayers));
    }

    @Override
    public @NonNull ValueResult<ContestantConfig> createDefaultConfig() {
        return ValueResult.success(new ContestantConfig(Collections.emptyList()));
    }

    public @Range(from = 0, to = Integer.MAX_VALUE) int getConfigPriority() {
        return ComponentPriority.HIGH.getValue();
    }

    // ==================== StartableComp 实现 ====================

    @Override
    public @NonNull DataKey<?> getSCK() {
        return DataKey.of(ContestantComp.class, ContestantStartContext.class);
    }

    @Override
    public @NonNull ValueResult<ContestantStartContext> install(@NonNull Match match, @Nullable ContestantStartArg startArg) {
        this.match = match;
        ContestantConfig config = match.config().dataOf(ContestantComp.CONFIG_KEY);
        if (config == null) {
            return ValueResult.failure(TranslateKeys.BaseComponents.CONTESTANT_COMP_NO_CONFIG);
        }

        // 初始化存活玩家和队伍计数
        for (Player player : config.initialContestants()) {
            joinMatchInternal(player);
        }

        return ValueResult.success(new ContestantStartContext(survivingPlayers.size()));
    }

    // ==================== EndableComp 实现 ====================

    @Override
    public @NonNull DataKey<?> getECK() {
        return END_KEY;
    }

    @Override
    public @NonNull ValueResult<ContestantEndContext> uninstall(@NonNull Match match, @Nullable ContestantEndArg endArg) {
        // 存活集合不在此清理：比赛结束事件中还有其他部分要遍历剩余存活者执行退赛
        return ValueResult.success(new ContestantEndContext(playerRanking(), teamRanking()));
    }

    // ==================== 公共 API ====================

    public void joinMatch(@NonNull Player player) {
        if (contains(player) || eliminatedPlayers.contains(player)) {
            return;
        }
        joinMatchInternal(player);
        // 参赛者变化由专属事件通知
        new PlayerJoinMatchEvent(player, match).callEvent();
    }

    private void joinMatchInternal(Player player) {
        survivingPlayers.add(player);
        Team team = TeamRoster.getInstance().getTeam(player);
        if (team != null) {
            teamAtJoin.put(player, team);
            survivingTeams.put(team, survivingTeams.getOrDefault(team, 0) + 1);
        }
    }

    public void leaveMatch(@NonNull Player player) {
        if (!contains(player)) {
            return;
        }
        survivingPlayers.remove(player);
        eliminatedPlayers.add(player);
        Team team = teamAtJoin.remove(player);
        if (team != null) {
            teamLeaveOrder.add(team);
            int count = survivingTeams.getOrDefault(team, 0);
            if (count <= 1) {
                survivingTeams.remove(team);
            } else {
                survivingTeams.put(team, count - 1);
            }
        }
        // 参赛者变化由专属事件通知
        new PlayerLeaveMatchEvent(player, match).callEvent();
    }

    /**
     * @param player 指定玩家
     *
     * @return 指定玩家是否在比赛中存活
     * */
    public boolean contains(@NonNull Player player) {
        return survivingPlayers.contains(player);
    }

    /**
     * @return 当前比赛中存活的所有玩家
     * */
    public Set<Player> survivingPlayers() {
        return Collections.unmodifiableSet(survivingPlayers);
    }

    /**
     * @return 当前比赛中所有存活的队伍，键为队伍；值为对应队伍的剩余（存活）玩家数
     * */
    public Map<@NonNull Team,@NonNull Integer> survivingTeams() {
        return Collections.unmodifiableMap(survivingTeams);
    }

    /**
     * @return 目前比赛中存活（剩余人数大于0）队伍的数量
     * */
    public int survivingTeamCount() {
        return survivingTeams.size();
    }

    /**
     * @return 所有已淘汰、不可加入比赛的玩家（按退赛先后排列）
     * */
    public Set<Player> eliminatedPlayers() {
        return Collections.unmodifiableSet(eliminatedPlayers);
    }

    /**
     * 玩家排行：先退赛者名次更高；仍未退赛的存活者视为未完成比赛，
     * 按加入顺序排在所有已退赛者之后
     *
     * @return 玩家排行快照，索引 0 为第一名
     * */
    public @NonNull List<Player> playerRanking() {
        List<Player> ranking = new ArrayList<>(eliminatedPlayers);
        ranking.addAll(survivingPlayers);
        return ranking;
    }

    /**
     * 队伍排行：按队伍首个成员退赛的先后排列；仍有存活成员但无人退赛的队伍排在末尾
     *
     * @return 队伍排行快照，索引 0 为第一名
     * */
    public @NonNull List<Team> teamRanking() {
        List<Team> ranking = new ArrayList<>(teamLeaveOrder);
        for (Player survivor : survivingPlayers) {
            Team team = teamAtJoin.get(survivor);
            if (team != null && !ranking.contains(team)) {
                ranking.add(team);
            }
        }
        return ranking;
    }

    /**
     * @param initialContestants 初始参赛者列表，比赛开始后参赛者组件将自动将其中的所有玩家加入比赛
     * */
    public record ContestantConfig(Collection<? extends Player> initialContestants) implements CustomData {
        @Override
        public @NonNull Class<? extends MatchComp> source() {
            return ContestantComp.class;
        }
    }

    /**
     * @param expectedContestants 调用者可以传入期望的初始参赛者列表（可空）
     */
    public record ContestantECFG(@NonNull Collection<? extends Player> expectedContestants) implements CustomData {
        @Override
        public @NonNull Class<? extends MatchComp> source() {
            return ContestantComp.class;
        }
    }

    public static class ContestantStartArg implements CustomData {
        @Override
        public @NonNull Class<? extends MatchComp> source() {
            return ContestantComp.class;
        }
    }

    /**
     * @param initialPlayerCount 开始上下文可以返回存活玩家快照等信息，此处简单实现
     */
    public record ContestantStartContext(int initialPlayerCount) implements CustomData {
        @Override
        public @NonNull Class<? extends MatchComp> source() {
            return ContestantComp.class;
        }
    }

    public static class ContestantEndArg implements CustomData {
        @Override
        public @NonNull Class<? extends MatchComp> source() {
            return ContestantComp.class;
        }
    }

    /**
     * 参赛者组件的结束上下文，两个列表都以索引 0 为第一名<br>
     * 排名依据退赛先后：先退赛者名次更高；比赛结束时仍未退赛的玩家视为未完成比赛，
     * 排在所有已退赛者之后
     *
     * @param playerRanking 玩家排行
     * @param teamRanking   队伍排行，按队伍首个成员退赛的先后排列；仍有存活成员但无成员退赛的队伍排于末尾
     * */
    public record ContestantEndContext(
            @NonNull List<Player> playerRanking,
            @NonNull List<Team> teamRanking
    ) implements CustomData {
        public ContestantEndContext {
            playerRanking = List.copyOf(playerRanking);
            teamRanking = List.copyOf(teamRanking);
        }

        @Override
        public @NonNull Class<? extends MatchComp> source() {
            return ContestantComp.class;
        }
    }
}
