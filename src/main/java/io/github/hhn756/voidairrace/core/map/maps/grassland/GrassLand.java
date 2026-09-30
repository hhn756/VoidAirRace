package io.github.hhn756.voidairrace.core.map.maps.grassland;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.constants.Plugin;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.addons.GameElementMeta;
import io.github.hhn756.voidairrace.core.map.PlayableGameMap;
import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.core.match.basecomponents.contestant.ContestantComp;
import io.github.hhn756.voidairrace.core.match.basecomponents.scope.AreaTags;
import io.github.hhn756.voidairrace.core.match.basecomponents.scope.MatchArea;
import io.github.hhn756.voidairrace.core.match.basecomponents.scope.ScopeComp;
import io.github.hhn756.voidairrace.core.matchrule.RuleComp;
import io.github.hhn756.voidairrace.core.team.TeamRoster;
import io.github.hhn756.voidairrace.exception.ArenaException;
import io.github.hhn756.voidairrace.infrastructure.config.Config;
import io.github.hhn756.voidairrace.infrastructure.util.schedulingutil.SchedulingUtil;
import io.github.hhn756.voidairrace.infrastructure.util.world.BlockRegion;
import io.github.hhn756.voidairrace.infrastructure.util.world.blockfinder.BlockFinder;
import io.github.hhn756.voidairrace.result.OperationResult;
import io.github.hhn756.voidairrace.result.ValueResult;
import io.github.hhn756.voidairrace.service.arena.ArenaManager;
import io.github.hhn756.voidairrace.service.arena.ArenaToken;
import io.papermc.paper.event.player.PlayerFailMoveEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.loot.LootTable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.Range;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class GrassLand extends PlayableGameMap implements Listener {
    private ArenaToken arena;
    private Match match;
    private static final GameElementMeta meta = new GameElementMeta(
            Const.MAP_ID,
            List.of(
                    Component.translatable(TranslateKeys.Map.GRASS_LAND_NAME)
            ),
            List.of(
                    Component.translatable(TranslateKeys.Map.GRASS_LAND_DESCRIPTION_LINE1)
            ),
            List.of(
                    Component.translatable(TranslateKeys.Map.GRASS_LAND_AUTHOR1)
            ),
            Component.translatable(TranslateKeys.Map.GRASS_LAND_DISPLAY_VERSION),
            1L,
            null
    );

    /**
     * 获取此地图的 ID
     * */
    public static NamespacedKey getID() {
        return Const.MAP_ID;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public @NonNull OperationResult start(@NonNull Match match) {
        this.match = match;

        // 加载竞技场；借用失败时把借用模块给出的原因挂到“草地地图开始失败”键对上
        ValueResult<ArenaToken> borrowResult = ArenaManager.getInstance().borrow();
        if (!(borrowResult instanceof ValueResult.WithValue(var arenaToken))) {
            return borrowResult.expectValue(
                    TranslateKeys.Map.GRASS_LAND_SELECTED_START_FAILURE_SPECIFIED_CAUSE,
                    TranslateKeys.Map.GRASS_LAND_SELECTED_START_FAILURE_UNKNOWN_CAUSE
            );
        }

        this.arena = arenaToken;
        arena.loadArena(resourcePath("arena"));
        match.comp(ScopeComp.class).widen(
                new MatchArea(arena, AreaTags.MAIN)
        );

        playerEntryArena(match);
        enableRules(match);
        deliverSupplies();

        return OperationResult.success();
    }

    private void playerEntryArena(Match match) {
        TeamRoster teamRoster = TeamRoster.getInstance();

        // 遍历参赛者列表让他们加入比赛（参赛者组件有配置对象，结果不会为 null）
        for (Player player : match.configOf(ContestantComp.CONFIG_KEY).initialContestants()) {
            Team playerTeam = teamRoster.getTeam(player);
            if (playerTeam != null) {
                Location loc = Const.TEAM_TO_SPAWN_LOCATION.get(playerTeam);
                loc.setWorld(arena.getWorld().value());
                player.teleport(loc);
            }
        }
    }

    /**
     * 填充地图上的所有补给箱
     */
    private void deliverSupplies() {
        // 获取箱子坐标列表
        List<Location> supplies = Config.getInstance().getYmlConfig(MapConfigFiles.DATA).get(MapConfigKeys.SUPPLY_CHESTS);
        if (supplies == null || supplies.isEmpty()) {
            return;
        }

        // 要刷新的物品
        LootTable supplyLoot = Bukkit.getLootTable(
                new NamespacedKey(Plugin.ns, "maps/void_air_race/grass_land/supply")
        );

        for (Location loc : supplies) {
            // 文件仅记录箱子位置，每局游戏都可能使用不同竞技场（不同世界）
            loc.setWorld(arena.getWorld().value());

            Block block = loc.getBlock();
            if (block.getState() instanceof Chest chest) {
                chest.getInventory().clear(); // 清空箱子，避免残留
                chest.setLootTable(supplyLoot);
                chest.update();
            }
        }
    }

    /**
     * 启用一些规则<br>
     * 规则启用失败时把原因挂到“草地地图开始失败”键对上并广播
     */
    private void enableRules(Match match) {
        RuleComp ruleComp = match.comp(RuleComp.class);

        for (NamespacedKey ruleId : Const.USE_RULES) {
            OperationResult enableRuleResult = ruleComp.enableRule(ruleId);
            if (!enableRuleResult.isSuccess()) {
                // 用户消息广播给玩家
                Component message = enableRuleResult
                        .causedBy(
                                TranslateKeys.Map.GRASS_LAND_SELECTED_START_FAILURE_SPECIFIED_CAUSE,
                                TranslateKeys.Map.GRASS_LAND_SELECTED_START_FAILURE_UNKNOWN_CAUSE
                        )
                        .message();
                if (message != null) {
                    Bukkit.getServer().broadcast(message.color(NamedTextColor.RED));
                }
                // 技术性消息只进日志
                String techMessage = enableRuleResult.techMessage();
                if (techMessage != null) {
                    VoidAirRace.getInstance().getLogger()
                            .warning("草地地图启用规则 " + ruleId + " 失败：" + techMessage);
                }
            }
        }
    }

    @Override
    public @Range(from = 1, to = Integer.MAX_VALUE) int maxTeams() {
        return 4;
    }

    @EventHandler
    public void onPlayerFailMove(PlayerFailMoveEvent event) {
        if (event.getPlayer().getLocation().getWorld()
                .equals(arena.getWorld().value())) {
            event.setLogWarning(false);
        }
    }

    @Override
    public @NonNull CompletableFuture<?> initAsync(JavaPlugin mainClass) {
        return findSupplyBoxes();
    }

    /**
     * 获取并储存所有物资箱子的位置
     */
    private CompletableFuture<?> findSupplyBoxes() {
        // 申请临时竞技场
        ValueResult<ArenaToken> borrowResult = ArenaManager.getInstance().borrow();
        if (!(borrowResult instanceof ValueResult.WithValue(var tempArena))) {
            return CompletableFuture.failedFuture(
                    new NullPointerException("未申请到临时竞技场，无法初始化")
            );
        }

        OperationResult loadArenaResult = tempArena.loadArena(resourcePath("arena"));
        if (!loadArenaResult.isSuccess()) {
            // 异常的 message 是技术性通道：记录结果的技术性消息；用户消息由翻译键给出
            String techMessage = loadArenaResult.techMessage();
            return CompletableFuture.failedFuture(
                    new ArenaException(
                            "加载竞技场失败" + (techMessage == null ? "" : "：" + techMessage),
                            Component.translatable(TranslateKeys.Map.GRASS_LAND_FIND_SUPPLY_BOXES_LOAD_ARENA_FAILURE)
                    )
            );
        }

        // 为每个区域创建异步扫描任务
        World world = tempArena.getWorld().value();
        if (world == null) {
            tempArena.returnArena();
            return CompletableFuture.failedFuture(
                    new IllegalStateException("无法获取竞技场世界，初始化失败")
            );
        }

        List<CompletableFuture<List<Location>>> futures = new ArrayList<>();
        for (BlockRegion area : Const.SUPPLY_BOX_SEARCH_RANGE) {
            futures.add(BlockFinder.findBlocksByMaterialAsync(
                    world,
                    area.getMinX(), area.getMinY(), area.getMinZ(),
                    area.getMaxX(), area.getMaxY(), area.getMaxZ(),
                    Material.CHEST));
        }

        // 等待所有扫描完成，合并结果并去重
        CompletableFuture<List<Location>> allLocationsFuture = CompletableFuture.allOf(
                        futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<Location> combined = new ArrayList<>();
                    for (CompletableFuture<List<Location>> f : futures) {
                        combined.addAll(f.join());
                    }
                    return combined.stream().distinct().collect(Collectors.toList());
                });

        // 保存结果并归还竞技场
        return allLocationsFuture.thenAcceptAsync(locations -> {
            for (Location loc : locations) {
                loc.setWorld(world);
            }
            Config.getInstance()
                    .getYmlConfig(MapConfigFiles.DATA)
                    .set(MapConfigKeys.SUPPLY_CHESTS, locations);
            tempArena.returnArena();
        }, SchedulingUtil::runOnMainThread);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location playerLoc = player.getLocation();
        if (!playerLoc.getWorld().equals( // 检查玩家是否在竞技场上
                arena.getWorld().value()
        )) return;

        // 到达终点后胜利
        if (playerLoc.z() >= 499.0d) {
            match.comp(ContestantComp.class).leaveMatch(player);
        }
    }

    @Override
    public @NonNull GameElementMeta getElementMeta() {
        return meta;
    }
}
