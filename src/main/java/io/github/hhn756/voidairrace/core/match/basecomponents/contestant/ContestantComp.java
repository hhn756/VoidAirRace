package io.github.hhn756.voidairrace.core.match.basecomponents.contestant;

import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.match.ComponentPriority;
import io.github.hhn756.voidairrace.core.match.DataKey;
import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.core.match.componentbase.ConfigurableComp;
import io.github.hhn756.voidairrace.core.match.componentbase.CustomData;
import io.github.hhn756.voidairrace.core.match.componentbase.MatchComp;
import io.github.hhn756.voidairrace.core.match.componentbase.StartableComp;
import io.github.hhn756.voidairrace.core.team.TeamRoster;
import io.github.hhn756.voidairrace.event.MatchStatusChangedEvent;
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
        ConfigurableComp<ContestantComp.ContestantECFG, ContestantComp.ContestantConfig> {

    // ==================== 组件内部状态 ====================

    /**
     * 存活的所有玩家
     * */
    private final Set<Player> survivingPlayers = new HashSet<>();

    /**
     * 存活队伍列表<br>
     * 键为剩余（存活）玩家数大于0的队伍，值为对应队伍的剩余（存活）玩家数
     * */
    private final Map<Team, Integer> survivingTeams = new HashMap<>();

    /**
     * 已淘汰、不可加入比赛的所有玩家
     * */
    private final Set<Player> eliminatedPlayers = new HashSet<>();
    private Match match;

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

    // ==================== 公共 API ====================

    public void joinMatch(@NonNull Player player) {
        if (contains(player) || eliminatedPlayers.contains(player)) {
            return;
        }
        joinMatchInternal(player);
        new PlayerJoinMatchEvent(player, match).callEvent();
        new MatchStatusChangedEvent(match).callEvent();
    }

    private void joinMatchInternal(Player player) {
        survivingPlayers.add(player);
        Team team = TeamRoster.getInstance().getTeam(player);
        if (team != null) {
            survivingTeams.put(team, survivingTeams.getOrDefault(team, 0) + 1);
        }
    }

    public void leaveMatch(@NonNull Player player) {
        if (!contains(player)) {
            return;
        }
        survivingPlayers.remove(player);
        eliminatedPlayers.add(player);
        Team team = TeamRoster.getInstance().getTeam(player);
        if (team != null) {
            int count = survivingTeams.getOrDefault(team, 0);
            if (count <= 0) {
                survivingTeams.remove(team);
            } else {
                survivingTeams.put(team, count - 1);
            }
        }
        new PlayerLeaveMatchEvent(player, match).callEvent();
        new MatchStatusChangedEvent(match).callEvent();
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
     * @return 所有已淘汰、不可加入比赛的玩家
     * */
    public Set<Player> eliminatedPlayers() {
        return Collections.unmodifiableSet(eliminatedPlayers);
    }

    /**
     * @param initialContestants 初始参赛者列表，比赛开始后参赛者组件将自动将其中的所有玩家加入比赛
     * */
    public static record ContestantConfig(Collection<? extends Player> initialContestants) implements CustomData {
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
        // 比赛开始时可以额外指定要加入的玩家（覆盖或追加），根据需要设计字段
        // 此处为空实现，可根据需要扩展
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
}
