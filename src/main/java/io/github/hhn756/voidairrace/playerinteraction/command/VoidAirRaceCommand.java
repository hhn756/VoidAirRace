package io.github.hhn756.voidairrace.playerinteraction.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.constants.Categories;
import io.github.hhn756.voidairrace.constants.PermissionNode;
import io.github.hhn756.voidairrace.constants.Plugin;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.map.GameMap;
import io.github.hhn756.voidairrace.core.map.MapEntry;
import io.github.hhn756.voidairrace.core.map.MapInitializer;
import io.github.hhn756.voidairrace.core.map.PlayableGameMap;
import io.github.hhn756.voidairrace.core.match.MatchCoordinator;
import io.github.hhn756.voidairrace.core.playerstatemanager.PlayerInitializer;
import io.github.hhn756.voidairrace.core.playerstatemanager.PlayerStateManager;
import io.github.hhn756.voidairrace.core.playerstatemanager.StateSystemEntry;
import io.github.hhn756.voidairrace.core.team.TeamRoster;
import io.github.hhn756.voidairrace.infrastructure.config.Config;
import io.github.hhn756.voidairrace.infrastructure.config.files.FlagsKeys;
import io.github.hhn756.voidairrace.infrastructure.config.files.GameSettingKeys;
import io.github.hhn756.voidairrace.infrastructure.config.files.PublicFiles;
import io.github.hhn756.voidairrace.infrastructure.modules.BootstrapStage;
import io.github.hhn756.voidairrace.infrastructure.registry.DefaultSubtable;
import io.github.hhn756.voidairrace.infrastructure.registry.Registry;
import io.github.hhn756.voidairrace.infrastructure.util.schedulingutil.SchedulingUtil;
import io.github.hhn756.voidairrace.result.OperationResult;
import io.github.hhn756.voidairrace.service.arena.ArenaManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Team;
import org.jspecify.annotations.NonNull;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 插件主命令 {@code voidairrace}（别名 {@code var}）：合并原 match、game_map、player_manager、vardebug 四个命令<br>
 * 语法结构：比赛控制归 {@code match}，地图管理（含查询与选中下一场比赛的地图）归 {@code map}，
 * 玩家初始化管理归 {@code player}，调试信息归 {@code debug}；
 * 各分支沿用原命令的权限节点（map 分支内部按子命令区分地图管理权限与比赛管理权限）
 * */
public class VoidAirRaceCommand implements BootstrapStage {
    public void onBootstrap(@NonNull BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commandsEvent -> {
            LiteralCommandNode<CommandSourceStack> node = Commands.literal("voidairrace")
                    .then(matchBranch())
                    .then(mapBranch())
                    .then(playerBranch())
                    .then(debugBranch())
                    .build();
            commandsEvent.registrar().register(node, "VoidAirRace 主命令", List.of("var"));
        });
    }

    /**
     * match 分支：比赛开始与结束（原 match 命令的功能）
     * */
    private static @NonNull LiteralCommandNode<CommandSourceStack> matchBranch() {
        return Commands.literal("match")
                .requires(ctx -> ctx.getSender().hasPermission(PermissionNode.MATCH_COMMAND.toString()))
                .then(Commands.literal("start")
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();

                            // 开始游戏
                            OperationResult startResult = MatchCoordinator.getInstance().startMatch(null);
                            if (!startResult.isSuccess()) {
                                // 用户消息给发送者，技术性消息进日志
                                Component message = startResult.message();
                                if (message == null) {
                                    message = Component.translatable(TranslateKeys.Command.MATCH_CMD_START_FAILURE);
                                }
                                sender.sendMessage(message.color(NamedTextColor.RED));
                                String techMessage = startResult.techMessage();
                                if (techMessage != null) {
                                    VoidAirRace.getInstance().getLogger().warning("开始比赛失败：" + techMessage);
                                }
                                return 0;
                            }

                            return 1;
                        })
                ).then(Commands.literal("stop")
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();

                            OperationResult stopResult = MatchCoordinator.getInstance().stopMatch();
                            if (!stopResult.isSuccess()) {
                                // 用户消息给发送者，技术性消息进日志
                                Component message = stopResult.message();
                                if (message == null) message = Component.translatable(
                                        TranslateKeys.Command.MATCH_CMD_STOP_FAILURE
                                );
                                sender.sendMessage(message.color(NamedTextColor.RED));
                                String techMessage = stopResult.techMessage();
                                if (techMessage != null) {
                                    VoidAirRace.getInstance().getLogger().warning("结束比赛失败：" + techMessage);
                                }
                                return 1;
                            }

                            sender.sendMessage(Component.translatable(TranslateKeys.Command.MATCH_CMD_STOP_SUCCESS));
                            return 1;
                        })
                )
                .build();
    }

    /**
     * map 分支：地图管理——list/reinit 是原 game_map 命令的功能，
     * set/get 是原 match set_map/get_map 命令的功能（查询与选中下一场比赛使用的地图）
     * */
    private static @NonNull LiteralCommandNode<CommandSourceStack> mapBranch() {
        return Commands.literal("map")
                .then(Commands.literal("list")
                        .requires(ctx -> ctx.getSender().hasPermission(PermissionNode.GAME_MAP_COMMAND.toString()))
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();

                            Collection<MapEntry> maps = Registry.getInstance().category(Categories.MAP).list();

                            sender.sendMessage(Component.translatable(TranslateKeys.Command.GAME_MAP_LIST_START));
                            for (MapEntry mapMeta : maps) {
                                GameMap mapInst = mapMeta.newInstance();

                                String translateKey = (mapInst instanceof PlayableGameMap ?
                                        TranslateKeys.Command.GAME_MAP_LIST_PLAYABLE_MAP_INFO
                                        : TranslateKeys.Command.GAME_MAP_LIST_NOT_PLAYABLE_MAP_INFO);

                                sender.sendMessage(Component.translatable(translateKey)
                                        .arguments(
                                                mapInst.getElementMeta().mainName(),
                                                Component.text(mapInst.getElementMeta().id().toString())
                                        )
                                );
                            }
                            sender.sendMessage(Component.translatable(TranslateKeys.Command.GAME_MAP_LIST_END));
                            return 1;
                        })
                ).then(Commands.literal("reinit")
                        .requires(ctx -> ctx.getSender().hasPermission(PermissionNode.GAME_MAP_COMMAND.toString()))
                        .then(Commands.argument("map_id", StringArgumentType.string())
                                .executes(ctx -> {
                                    CommandSender sender = ctx.getSource().getSender();
                                    NamespacedKey targetMapId = NamespacedKey.fromString(
                                            ctx.getArgument("map_id", String.class)
                                    );
                                    if (targetMapId == null) {
                                        sender.sendMessage(
                                                Component.translatable(
                                                        TranslateKeys.Command.GAME_MAP_REINIT_ID_FORMAT_ERROR
                                                )
                                        );
                                        return 1;
                                    }

                                    MapEntry mapEntry = Registry.getInstance().category(Categories.MAP).get(targetMapId);
                                    if (mapEntry == null) {
                                        sender.sendMessage(
                                                Component.translatable(
                                                        TranslateKeys.Command.GAME_MAP_REINIT_MAP_NOT_FOUND
                                                )
                                        );
                                        return 1;
                                    }
                                    sender.sendMessage(
                                            Component.translatable(TranslateKeys.Command.GAME_MAP_REINIT_STARTED)
                                    );
                                    MapInitializer.getInstance().reinitMap(targetMapId).thenRunAsync(
                                            () -> sender.sendMessage(
                                                    Component.translatable(TranslateKeys.Command.GAME_MAP_REINIT_OK)
                                                            .arguments(mapEntry.getElementMeta().mainName())
                                            ),
                                            SchedulingUtil::runOnMainThread
                                    );
                                    return 1;
                                })
                        )
                ).then(Commands.literal("set")
                        .requires(ctx -> ctx.getSender().hasPermission(PermissionNode.MATCH_COMMAND.toString()))
                        .then(Commands.argument("map_id", StringArgumentType.string())
                                .executes(ctx -> {
                                    CommandSender sender = ctx.getSource().getSender();
                                    String rawMapId = ctx.getArgument("map_id", String.class);
                                    NamespacedKey formatedMapId = NamespacedKey.fromString(rawMapId);
                                    Registry registry = Registry.getInstance();
                                    DefaultSubtable<MapEntry, NamespacedKey> mapSubtable = registry.category(Categories.MAP);

                                    // 如果输入格式错误
                                    if (formatedMapId == null) {
                                        sender.sendMessage(
                                                Component.translatable(TranslateKeys.Command.MATCH_CMD_SET_MAP_ID_FORMAT_ERROR)
                                                        .color(NamedTextColor.RED));
                                        return 1;
                                    }

                                    // 默认使用本插件的命名空间
                                    if (!rawMapId.contains(":")) {
                                        formatedMapId = new NamespacedKey(Plugin.ns, formatedMapId.getKey());
                                    }

                                    // 检查地图是否存在
                                    MapEntry mapEntry = mapSubtable.get(formatedMapId);
                                    if (mapEntry == null) {
                                        sender.sendMessage(
                                                Component.translatable(TranslateKeys.Command.MATCH_CMD_SET_MAP_MAP_NOTFOUND)
                                                        .arguments(Component.text(formatedMapId.toString()))
                                                        .color(NamedTextColor.RED)
                                        );
                                        return 1;
                                    }

                                    // 检查地图是否可玩
                                    if (!mapEntry.isPlayable()) {
                                        sender.sendMessage(
                                                Component.translatable(TranslateKeys.Command.MATCH_CMD_SET_MAP_MAP_NOT_PLAYABLE)
                                                        .arguments(Component.text(rawMapId))
                                                        .color(NamedTextColor.RED)
                                        );
                                        return 1;
                                    }

                                    // 选中指定地图
                                    Config.getInstance()
                                            .getYmlConfig(PublicFiles.GAME_SETTINGS)
                                            .set(GameSettingKeys.SELECTED_MAP_ID, formatedMapId.toString());

                                    // 提示
                                    sender.sendMessage(
                                            Component.translatable(TranslateKeys.Command.MATCH_CMD_SET_MAP_SUCCESS)
                                                    .arguments(
                                                            Component.text(formatedMapId.toString()),   // 地图 id
                                                            mapEntry.getElementMeta().mainName()         // 地图显示名
                                                    )
                                    );
                                    return 1;
                                })
                        )
                ).then(Commands.literal("get")
                        .requires(ctx -> ctx.getSender().hasPermission(PermissionNode.MATCH_COMMAND.toString()))
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            NamespacedKey selectedMapId = NamespacedKey.fromString(
                                    Config.getInstance()
                                            .getYmlConfig(PublicFiles.GAME_SETTINGS)
                                            .get(GameSettingKeys.SELECTED_MAP_ID, null)
                            );

                            MapEntry mapEntry = Registry.getInstance().category(Categories.MAP).get(selectedMapId);
                            sender.sendMessage(
                                    Component.translatable(TranslateKeys.Command.MATCH_CMD_GET_MAP_SUCCESS)
                                            .arguments(
                                                    Component.text(selectedMapId == null
                                                            ? "null"
                                                            : selectedMapId.toString()
                                                    ),
                                                    mapEntry == null
                                                        ? Component.translatable(
                                                                TranslateKeys.Command.MATCH_CMD_GET_MAP_DEFAULT_MAP_NAME)
                                                        : mapEntry.getElementMeta().mainName()
                                            )
                            );
                            return 1;
                        })
                )
                .build();
    }

    /**
     * player 分支：玩家初始化管理（原 player_manager 命令的功能；
     * 原命令中未实现的 state get 查询桩不随合并保留）
     * */
    private static @NonNull LiteralCommandNode<CommandSourceStack> playerBranch() {
        return Commands.literal("player")
                .requires(ctx -> ctx.getSender().hasPermission(PermissionNode.PLAYER_MANAGER_COMMAND.toString()))
                .then(Commands.literal("init")
                        .then(Commands.literal("get")
                                .then(Commands.argument("player", StringArgumentType.string())
                                        .executes(ctx -> {
                                            CommandSender sender = ctx.getSource().getSender();
                                            Player targetPlayer = Bukkit.getPlayer(ctx.getArgument("player", String.class));
                                            if (targetPlayer == null) {
                                                sender.sendMessage(Component.translatable(TranslateKeys.Command.PLAYER_MANAGER_INIT_GET_NULL_PLAYER)
                                                        .arguments(Component.text(ctx.getArgument("player", String.class)))
                                                        .color(NamedTextColor.RED)
                                                );
                                                return 1;
                                            }
                                            String key = PlayerInitializer.getInstance().isInitialized(targetPlayer)
                                                    ? TranslateKeys.Command.PLAYER_MANAGER_INIT_GET_YES
                                                    : TranslateKeys.Command.PLAYER_MANAGER_INIT_GET_NO;
                                            sender.sendMessage(Component.translatable(key)
                                                    .arguments(Component.text(targetPlayer.getName())));
                                            return 1;
                                        })
                                )
                        ).then(Commands.literal("reinit")
                                .then(Commands.argument("player", StringArgumentType.string())
                                        .executes(ctx -> {
                                            CommandSender sender = ctx.getSource().getSender();
                                            Player targetPlayer = Bukkit.getPlayer(ctx.getArgument("player", String.class));
                                            if (targetPlayer == null) {
                                                sender.sendMessage(Component.translatable(TranslateKeys.Command.PLAYER_MANAGER_INIT_REINIT_NULL_PLAYER)
                                                        .arguments(Component.text(ctx.getArgument("player", String.class)))
                                                        .color(NamedTextColor.RED)
                                                );
                                                return 1;
                                            }
                                            PlayerInitializer.getInstance().reInitPlayer(targetPlayer);
                                            sender.sendMessage(Component.translatable(TranslateKeys.Command.PLAYER_MANAGER_INIT_REINIT_OK)
                                                    .arguments(Component.text(targetPlayer.getName()))
                                            );
                                            return 1;
                                        })
                                )
                        )
                )
                .build();
    }

    /**
     * debug 分支：调试信息（原 vardebug 命令的功能，供开发和调试使用）
     * */
    private static @NonNull LiteralCommandNode<CommandSourceStack> debugBranch() {
        return Commands.literal("debug")
                .requires(ctx -> ctx.getSender().hasPermission(PermissionNode.DEBUG_COMMAND.toString()))
                .then(Commands.literal("info")
                        .then(Commands.literal("player")
                                .executes(ctx -> {
                                    if (!(ctx.getSource().getSender() instanceof Player sender)) return 1;

                                    Server server = Bukkit.getServer();

                                    server.broadcast(Component.text("------ [玩家信息] ------"));

                                    Team playerTeam = TeamRoster.getInstance().getTeam(sender);
                                    if (playerTeam != null) {
                                        server.broadcast(Component.text(sender.getName() + "的当前队伍："
                                                        + TeamRoster.getInstance().teamToEnum(
                                                                TeamRoster.getInstance().getTeam(sender)
                                                        )
                                                        .id())
                                        );
                                    } else {
                                        server.broadcast(Component.text(sender.getName() + "的当前队伍：无"));
                                    }

                                    server.broadcast(Component.text(sender.getName() + "的初始化状态：" + PlayerInitializer.getInstance().isInitialized(sender)));

                                    server.broadcast(Component.text(sender.getName() + "在所有状态体系的当前状态："));
                                    Map<String, String> states = PlayerStateManager.getInstance().getAllStates(sender);
                                    for (Map.Entry<String, String> entry : states.entrySet()) {
                                        server.broadcast(Component.text("-" + entry.getKey() + ": " + entry.getValue()));
                                    }
                                    if (states.isEmpty()) server.broadcast(Component.text("（没有任何状态）"));

                                    server.broadcast(Component.text("------"));

                                    return 1;
                                })
                        ).then(Commands.literal("server")
                                .executes(ctx -> {
                                    Server server = Bukkit.getServer();

                                    server.broadcast(Component.text("------ [服务器信息] ------"));

                                    server.broadcast(Component.text("已注册状态体系列表："));
                                    Collection<StateSystemEntry> stateSystems = Registry.getInstance()
                                            .category(Categories.STATESYSTEM)
                                            .list();
                                    for (StateSystemEntry state : stateSystems) {
                                        server.broadcast(Component.text("- " + state.toString()));
                                    }

                                    server.broadcast(
                                            Component.text("当前比赛状态："
                                                    + MatchCoordinator.getInstance()
                                                    .getMatchState()
                                            )
                                    );
                                    server.broadcast(Component.text(
                                            "“服务器启动时结束比赛” 标志状态："
                                                    + Config.getInstance()
                                                    .getYmlConfig(PublicFiles.FLAGS)
                                                    .get(FlagsKeys.MATCH_ABORTED))
                                    );

                                    ArenaManager arenaManager = ArenaManager.getInstance();
                                    server.broadcast(
                                            Component.text("竞技场："
                                                    + arenaManager.getFreeCount()
                                                    + "自由 / " + arenaManager.getMaxArenas()
                                                    + "最大")
                                    );

                                    server.broadcast(Component.text("------"));
                                    return 0;
                                })
                        )
                )
                .build();
    }
}
