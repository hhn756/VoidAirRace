package io.github.hhn756.voidairrace.core.team;

import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jspecify.annotations.NonNull;

import java.util.*;

/**
 * 管理玩家队伍，只允许使用 {@link Teams} 枚举中定义的队伍
 * */
public class TeamRoster implements Module {
    private static TeamRoster instance;

    public static TeamRoster getInstance() {
        if (instance == null) throw new NullPointerException("队伍花名册实例不存在");
        return instance;
    }

    // ------

    private @NonNull Scoreboard teamScb;
    private final @NonNull LinkedHashMap<Teams, Team> enumToTeamMap = new LinkedHashMap<>();
    private final @NonNull LinkedHashMap<Team, Teams> teamToEnumMap = new LinkedHashMap<>();

    private TeamRoster() {}

    @Override
    public Collection<Class<? extends Module>> getRequiredModules() {
        return List.of();
    }

    /**
     * 插件启用时执行<br>
     * 创建队伍等有副作用的工作必须在此完成：Modules 会先实例化全部模块，再按拓扑顺序加载
     * */
    private void onLoad() {
        // 创建队伍
        teamScb = Bukkit.getScoreboardManager().getNewScoreboard();
        for (Teams teamConfig : Teams.values()) {
            // 创建
            Team teamInst = teamScb.registerNewTeam(teamConfig.id());

            // 记录
            enumToTeamMap.put(teamConfig, teamInst);
            teamToEnumMap.put(teamInst, teamConfig);

            // 设置
            teamInst.prefix(teamConfig.prefix());                                        // 前缀
            teamInst.displayName(teamConfig.displayName());                              // 显示名
            teamInst.setAllowFriendlyFire(false);                                        // 友伤
            teamInst.setCanSeeFriendlyInvisibles(true);                                  // 隐身队友可见性
            teamInst.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);     // 碰撞规则
            teamInst.color(teamConfig.color());                                          // 队伍颜色
        }

        instance = this;
    }

    /** 插件禁用时执行 */
    private void onUnload() {
        instance = null;
    }

    /**
     * 将指定实体添加到指定队伍
     *
     * @param entity 指定实体
     * @param team 指定队伍
     */
    public void join(@NonNull Entity entity, @NonNull Teams team) {
        Team teamInst = enumToTeam(team);
        if (onTeam(entity, team)) return;
        if (entity instanceof Player player) player.setScoreboard(teamScb);
        teamInst.addEntity(entity);
    }

    /**
     * 使指定实体离开它的队伍
     *
     * @param entity 指定实体
     */
    public void leave(@NonNull Entity entity) {
        Team team = getTeam(entity);
        if (team == null) return;
        if (entity instanceof Player player) player.setScoreboard(teamScb);
        team.removeEntity(entity);
    }

    /**
     * 检查指定实体是否在指定队伍中
     *
     * @param entity 指定实体
     * @param team   指定队伍
     *
     * @return 如果在队伍中则返回{@code true}，否则返回{@code false}
     */
    public boolean onTeam(@NonNull Entity entity, @NonNull Teams team){
        return enumToTeam(team).hasEntity(entity);
    }

    /**
     * 检查指定实体是否在指定队伍中
     *
     * @param entity 指定实体
     * @param team   指定队伍
     *
     * @return 如果在队伍中则返回{@code true}，否则返回{@code false}
     */
    public boolean onTeam(@NonNull Entity entity, @NonNull Team team){
        return team.hasEntity(entity);
    }

    /**
     * 获取指定实体所在的队伍
     *
     * @param entity 指定实体
     * @return 队伍实例
     * */
    public Team getTeam(@NonNull Entity entity) throws IllegalStateException {
        return teamScb.getEntityTeam(entity);
    }

    /**
     * 获取队伍枚举值对应的队伍实例
     * */
    public Team enumToTeam(@NonNull Teams team) {
        return enumToTeamMap.get(team);
    }

    /**
     * 获取队伍实例对应的队伍枚举值，
     * 如果传入的对象不是本类实例化的队伍实例则返回 {@code null}
     * */
    public Teams teamToEnum(@NonNull Team team) {
        return teamToEnumMap.get(team);
    }

    /**
     * 获取所有队伍实例
     *
     * @return 队伍实例列表的不可变视图
     * */
    public @NonNull Collection<Team> getAllTeams() {
        return Collections.unmodifiableCollection(enumToTeamMap.values());
    }

    /**
     * 获取队伍集合中的前 n 个队伍实例
     * */
    public @NonNull List<Team> getFirstNTeams(int n) {
        if (n <= 0) return Collections.emptyList();
        List<Team> allTeams = new ArrayList<>(enumToTeamMap.values()); // teamMap 按枚举顺序插入，所以顺序固定
        return allTeams.subList(0, Math.min(n, allTeams.size()));
    }
}
