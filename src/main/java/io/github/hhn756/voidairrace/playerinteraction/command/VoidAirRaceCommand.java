package io.github.hhn756.voidairrace.playerinteraction.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.hhn756.voidairrace.Voidairrace;
import io.github.hhn756.voidairrace.addons.GameElement;
import io.github.hhn756.voidairrace.addons.GameElementMeta;
import io.github.hhn756.voidairrace.addons.usrpackage.PackageLoader;
import io.github.hhn756.voidairrace.addons.usrpackage.PackageManager;
import io.github.hhn756.voidairrace.addons.usrpackage.PackageRoot;
import io.github.hhn756.voidairrace.addons.usrpackage.UsrPackage;
import io.github.hhn756.voidairrace.constants.Categories;
import io.github.hhn756.voidairrace.constants.PermissionNode;
import io.github.hhn756.voidairrace.constants.Plugin;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.map.GameMap;
import io.github.hhn756.voidairrace.core.map.MapEntry;
import io.github.hhn756.voidairrace.core.map.MapInitializer;
import io.github.hhn756.voidairrace.core.map.PlayableGameMap;
import io.github.hhn756.voidairrace.core.match.MatchConfig;
import io.github.hhn756.voidairrace.core.match.MatchCoordinator;
import io.github.hhn756.voidairrace.core.match.component.contestant.ContestantComp;
import io.github.hhn756.voidairrace.core.playerstatemanager.PlayerInitializer;
import io.github.hhn756.voidairrace.core.playerstatemanager.PlayerStateManager;
import io.github.hhn756.voidairrace.core.playerstatemanager.StateSystemEntry;
import io.github.hhn756.voidairrace.core.playerstatemanager.systems.play.PlayState;
import io.github.hhn756.voidairrace.core.team.TeamRoster;
import io.github.hhn756.voidairrace.core.team.Teams;
import io.github.hhn756.voidairrace.exception.ConfigException;
import io.github.hhn756.voidairrace.infrastructure.config.Config;
import io.github.hhn756.voidairrace.infrastructure.config.YamlConfig;
import io.github.hhn756.voidairrace.infrastructure.config.file.FlagKey;
import io.github.hhn756.voidairrace.infrastructure.config.file.GameSettingKey;
import io.github.hhn756.voidairrace.infrastructure.config.file.GlobalSettingKey;
import io.github.hhn756.voidairrace.infrastructure.config.file.PublicFile;
import io.github.hhn756.voidairrace.infrastructure.language.ServerLanguage;
import io.github.hhn756.voidairrace.infrastructure.modules.BootstrapStage;
import io.github.hhn756.voidairrace.infrastructure.modules.Modules;
import io.github.hhn756.voidairrace.infrastructure.registry.DefaultSubtable;
import io.github.hhn756.voidairrace.infrastructure.registry.Registry;
import io.github.hhn756.voidairrace.infrastructure.util.schedulingutil.SchedulingUtil;
import io.github.hhn756.voidairrace.result.OperationResult;
import io.github.hhn756.voidairrace.result.ValueResult;
import io.github.hhn756.voidairrace.service.arena.ArenaManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Team;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.logging.Level;

/**
 * 插件主命令 {@code voidairrace}（别名 {@code var}）：合并原 match、game_map、player_manager、vardebug 四个命令<br>
 * 语法结构：比赛控制归 {@code match}，地图管理（含查询与选中下一场比赛的地图）归 {@code map}，
 * 玩家初始化管理归 {@code player}，用户包管理归 {@code usrpackage}，调试信息归 {@code debug}；
 * 各分支沿用原命令的权限节点（map 分支内部按子命令区分地图管理权限与比赛管理权限）
 * */
public class VoidAirRaceCommand implements BootstrapStage {
    public void onBootstrap(@NonNull BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commandsEvent -> {
            LiteralCommandNode<CommandSourceStack> node = Commands.literal("voidairrace")
                    .then(matchBranch())
                    .then(mapBranch())
                    .then(playerBranch())
                    .then(teamBranch())
                    .then(usrPackageBranch())
                    .then(debugBranch())
                    .build();
            commandsEvent.registrar().register(node, "Voidairrace 主命令", List.of("var"));
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

                            // 初始参赛者取大厅世界中的空闲玩家
                            ValueResult<MatchConfig> configResult = MatchConfig.create(
                                    new ContestantComp.ContestantECFG(collectLobbyContestants()));

                            // 开始游戏；配置创建失败时把原因挂到“开始比赛失败”键对上
                            OperationResult startResult = configResult instanceof ValueResult.WithValue(var matchConfig)
                                    ? MatchCoordinator.getInstance().startMatch(matchConfig)
                                    : configResult.expectValue(
                                            TranslateKeys.Match.START_START_FAILED_SPECIFIED_REASONS,
                                            TranslateKeys.Match.START_START_FAILED_UNKNOWN_REASONS
                                    );
                            if (!startResult.isSuccess()) {
                                // 用户消息给发送者，技术性消息进日志
                                Component message = startResult.message();
                                if (message == null) {
                                    message = Component.translatable(TranslateKeys.Command.MATCH_CMD_START_FAILURE);
                                }
                                sender.sendMessage(message.color(NamedTextColor.RED));
                                String techMessage = startResult.techMessage();
                                if (techMessage != null) {
                                    Voidairrace.getInstance().getLogger().warning(
                                            ServerLanguage.translate(
                                                    TranslateKeys.Log.COMMAND_MATCH_START_FAILED, techMessage));
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
                                    Voidairrace.getInstance().getLogger().warning(
                                            ServerLanguage.translate(
                                                    TranslateKeys.Log.COMMAND_MATCH_STOP_FAILED, techMessage));
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
     * 收集大厅世界中处于空闲状态的玩家，作为比赛开始时的初始参赛者<br>
     * 只认 play 状态体系下的 free，已参赛、状态未初始化或不在该世界的玩家都不算
     *
     * @return 初始参赛者快照；大厅世界不可用时为空列表
     * */
    private static @NonNull List<Player> collectLobbyContestants() {
        World lobbyWorld = lobbyWorld();
        if (lobbyWorld == null) return List.of();

        return lobbyWorld.getPlayers().stream()
                .filter(player -> PlayerStateManager.getInstance().onState(player, PlayState.FREE.getId()))
                .toList();
    }

    /**
     * @return 全局设置中重生点所在的世界（大厅世界）；世界未加载时为 {@code null}
     * */
    private static @Nullable World lobbyWorld() {
        Location spawnLocation = Config.getInstance()
                .getYmlConfig(PublicFile.GLOBAL_SETTING)
                .get(GlobalSettingKey.SPAWN_LOCATION);
        return spawnLocation.getWorld();
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
                                            .getYmlConfig(PublicFile.GAME_SETTING)
                                            .set(GameSettingKey.SELECTED_MAP_ID, formatedMapId.toString());

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
                                            .getYmlConfig(PublicFile.GAME_SETTING)
                                            .get(GameSettingKey.SELECTED_MAP_ID, null)
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
     * team 分支：玩家在大厅选择队伍<br>
     * 选队不是管理操作，权限节点默认授予所有玩家；可选的队伍数量取当前比赛地图的最大队伍数
     * */
    private static @NonNull LiteralCommandNode<CommandSourceStack> teamBranch() {
        return Commands.literal("team")
                .requires(ctx -> ctx.getSender().hasPermission(PermissionNode.TEAM_COMMAND.toString()))
                .executes(ctx -> showTeam(ctx.getSource().getSender()))
                .then(Commands.literal("join")
                        .then(Commands.argument("team", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    int available = availableTeamCount();
                                    Teams[] teams = Teams.values();
                                    for (int i = 0; i < available && i < teams.length; i++) {
                                        builder.suggest(teams[i].id());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> joinTeam(
                                        ctx.getSource().getSender(),
                                        ctx.getArgument("team", String.class)
                                ))
                        )
                )
                .then(Commands.literal("leave")
                        .executes(ctx -> leaveTeam(ctx.getSource().getSender()))
                )
                .build();
    }

    /**
     * {@code team}：显示当前队伍
     * */
    private static int showTeam(@NonNull CommandSender sender) {
        if (!(sender instanceof Player player)) return playerOnly(sender);

        Team currentTeam = TeamRoster.getInstance().getTeam(player);
        Teams team = currentTeam == null ? null : TeamRoster.getInstance().teamToEnum(currentTeam);
        if (team == null) {
            player.sendMessage(Component.translatable(TranslateKeys.Command.TEAM_CURRENT_NONE));
            return 1;
        }
        player.sendMessage(Component.translatable(TranslateKeys.Command.TEAM_CURRENT_TEAM)
                .arguments(team.displayName()));
        return 1;
    }

    /**
     * {@code team join <队伍>}：加入指定队伍；接受队伍 id（大小写不敏感）或 1 起始的序号
     * */
    private static int joinTeam(@NonNull CommandSender sender, @NonNull String teamId) {
        if (!(sender instanceof Player player)) return playerOnly(sender);

        String rejection = teamSelectionRejection(player);
        if (rejection != null) {
            player.sendMessage(Component.translatable(rejection).color(NamedTextColor.RED));
            return 1;
        }

        int available = availableTeamCount();
        if (available == 0) {
            player.sendMessage(Component.translatable(TranslateKeys.Command.TEAM_NO_AVAILABLE_TEAMS)
                    .color(NamedTextColor.RED));
            return 1;
        }

        Teams target = findTeam(teamId);
        if (target == null || target.ordinal() >= available) {
            player.sendMessage(Component.translatable(TranslateKeys.Command.TEAM_UNKNOWN)
                    .arguments(Component.text(teamId), Component.text(availableTeamIds(available)))
                    .color(NamedTextColor.RED));
            return 1;
        }

        TeamRoster.getInstance().join(player, target);
        player.sendMessage(Component.translatable(TranslateKeys.Command.TEAM_JOIN_SUCCESS)
                .arguments(target.displayName()));
        return 1;
    }

    /**
     * {@code team leave}：退出当前队伍
     * */
    private static int leaveTeam(@NonNull CommandSender sender) {
        if (!(sender instanceof Player player)) return playerOnly(sender);

        String rejection = teamSelectionRejection(player);
        if (rejection != null) {
            player.sendMessage(Component.translatable(rejection).color(NamedTextColor.RED));
            return 1;
        }

        TeamRoster.getInstance().leave(player);
        player.sendMessage(Component.translatable(TranslateKeys.Command.TEAM_LEAVE_SUCCESS));
        return 1;
    }

    /** 非玩家执行队伍命令时的统一回复（队伍命令的上下文是玩家自身） */
    private static int playerOnly(@NonNull CommandSender sender) {
        sender.sendMessage(Component.translatable(TranslateKeys.Command.TEAM_PLAYER_ONLY)
                .color(NamedTextColor.RED));
        return 1;
    }

    /**
     * 选队前置检查：只有大厅世界中的空闲玩家可以选队
     *
     * @return 拒绝原因对应的翻译键；允许选队时为 {@code null}
     * */
    private static @Nullable String teamSelectionRejection(@NonNull Player player) {
        if (!PlayerStateManager.getInstance().onState(player, PlayState.FREE.getId())) {
            return TranslateKeys.Command.TEAM_NOT_FREE;
        }
        World lobbyWorld = lobbyWorld();
        if (lobbyWorld == null || !player.getWorld().equals(lobbyWorld)) {
            return TranslateKeys.Command.TEAM_NOT_IN_LOBBY;
        }
        return null;
    }

    /**
     * @return 当前比赛地图允许的队伍数量；地图未选中或不可游玩时为 0
     * */
    private static int availableTeamCount() {
        String rawMapId = Config.getInstance()
                .getYmlConfig(PublicFile.GAME_SETTING)
                .get(GameSettingKey.SELECTED_MAP_ID, null);
        NamespacedKey mapId = rawMapId == null ? null : NamespacedKey.fromString(rawMapId);
        if (mapId == null) return 0;

        MapEntry mapEntry = Registry.getInstance().category(Categories.MAP).get(mapId);
        Integer maxTeams = mapEntry == null ? null : mapEntry.maxTeams();
        return maxTeams == null ? 0 : maxTeams;
    }

    /**
     * 解析队伍参数：接受队伍 id（大小写不敏感）或 1 起始的序号
     *
     * @return 对应的队伍；两种格式都不匹配时为 {@code null}
     * */
    private static @Nullable Teams findTeam(@NonNull String raw) {
        for (Teams team : Teams.values()) {
            if (team.id().equalsIgnoreCase(raw)) return team;
        }

        int number;
        try {
            number = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
        if (number < 1 || number > Teams.values().length) return null;
        return Teams.values()[number - 1];
    }

    /**
     * @return 可选的队伍，格式为「序号 id」，用顿号连接，用于错误消息与提示
     * */
    private static @NonNull String availableTeamIds(int available) {
        Teams[] teams = Teams.values();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < available && i < teams.length; i++) ids.add((i + 1) + " " + teams[i].id());
        return String.join("、", ids);
    }

    /**
     * usrpackage 分支：用户包的查看与装卸
     * <ul>
     *   <li>{@code list [all|loaded|unloaded]}：列出包目录中的包（每次执行重新扫描目录）</li>
     *   <li>{@code elements <包Id> [元素类型]}：列出该包注册的游戏元素，可按 map/rule/tag 过滤</li>
     *   <li>{@code enable <包Id>}：写入启用列表后加载</li>
     *   <li>{@code disable <包Id>}：从启用列表移除后卸载</li>
     * </ul>
     * */
    private static @NonNull LiteralCommandNode<CommandSourceStack> usrPackageBranch() {
        return Commands.literal("usrpackage")
                .requires(ctx -> ctx.getSender().hasPermission(PermissionNode.USR_PACKAGE_COMMAND.toString()))
                .then(Commands.literal("list")
                        .executes(ctx -> listUsrPackages(ctx.getSource().getSender(), PackageListMode.ALL))
                        .then(Commands.literal("all")
                                .executes(ctx -> listUsrPackages(ctx.getSource().getSender(), PackageListMode.ALL)))
                        .then(Commands.literal("loaded")
                                .executes(ctx -> listUsrPackages(ctx.getSource().getSender(), PackageListMode.LOADED)))
                        .then(Commands.literal("unloaded")
                                .executes(ctx -> listUsrPackages(ctx.getSource().getSender(), PackageListMode.UNLOADED)))
                ).then(Commands.literal("elements")
                        .then(Commands.argument("package_id", StringArgumentType.string())
                                .executes(ctx -> listPackageElements(
                                        ctx.getSource().getSender(),
                                        ctx.getArgument("package_id", String.class),
                                        null
                                ))
                                .then(Commands.argument("element_type", StringArgumentType.word())
                                        .executes(ctx -> listPackageElements(
                                                ctx.getSource().getSender(),
                                                ctx.getArgument("package_id", String.class),
                                                ctx.getArgument("element_type", String.class)
                                        ))
                                )
                        )
                ).then(Commands.literal("enable")
                        .then(Commands.argument("package_id", StringArgumentType.string())
                                .executes(ctx -> enableUsrPackage(
                                        ctx.getSource().getSender(),
                                        ctx.getArgument("package_id", String.class)
                                ))
                        )
                ).then(Commands.literal("disable")
                        .then(Commands.argument("package_id", StringArgumentType.string())
                                .executes(ctx -> disableUsrPackage(
                                        ctx.getSource().getSender(),
                                        ctx.getArgument("package_id", String.class)
                                ))
                        )
                )
                .build();
    }

    /**
     * 用户包列表的筛选模式
     * */
    private enum PackageListMode {
        /** 全部包（已加载与未加载） */
        ALL,
        /** 仅已加载 */
        LOADED,
        /** 仅未加载 */
        UNLOADED
    }

    /**
     * 列出用户包目录中的包：每次执行都重新扫描目录，运行期放进来的包也能看到
     *
     * @param sender 命令发送者
     * @param mode   筛选模式
     *
     * @return 命令返回值
     * */
    private static int listUsrPackages(@NonNull CommandSender sender, @NonNull PackageListMode mode) {
        PackageManager packageManager = PackageManager.getInstance();
        sender.sendMessage(Component.translatable(TranslateKeys.Command.USR_PACKAGE_LIST_START));

        int shown = 0;
        for (PackageRoot root : Modules.getModule(PackageLoader.class).scan()) {
            UsrPackage meta = packageManager.readMeta(root).value();
            String packageId = meta == null ? null : meta.id();
            boolean loaded = packageId != null && packageManager.isLoaded(packageId);
            if (mode == PackageListMode.LOADED && !loaded) continue;
            if (mode == PackageListMode.UNLOADED && loaded) continue;
            shown++;

            // 元文件读不出来时只剩来源可展示
            if (packageId == null) {
                sender.sendMessage(Component.translatable(
                        TranslateKeys.Command.USR_PACKAGE_LIST_ENTRY_UNIDENTIFIED
                ).arguments(Component.text(PackageLoader.sourceName(root))));
                continue;
            }
            sender.sendMessage(Component.translatable(
                    loaded ? TranslateKeys.Command.USR_PACKAGE_LIST_ENTRY_LOADED
                            : TranslateKeys.Command.USR_PACKAGE_LIST_ENTRY_UNLOADED
            ).arguments(
                    Component.text(packageId),
                    Component.text(PackageLoader.sourceName(root))
            ));
        }

        if (shown == 0) {
            sender.sendMessage(Component.translatable(TranslateKeys.Command.USR_PACKAGE_LIST_EMPTY));
        }
        sender.sendMessage(Component.translatable(TranslateKeys.Command.USR_PACKAGE_LIST_END));
        return 1;
    }

    /**
     * 列出某个已加载用户包注册的游戏元素<br>
     * 归属按元素 Id 的命名空间判定（注册时自动加包 Id 前缀），元素类型缺省（或 {@code all}）时列出
     * map/rule/tag 三类
     *
     * @param sender    命令发送者
     * @param packageId 包 Id
     * @param rawType   元素类型字符串，{@code null} 或 {@code all} 表示不筛选
     *
     * @return 命令返回值
     * */
    private static int listPackageElements(
            @NonNull CommandSender sender,
            @NonNull String packageId,
            @Nullable String rawType
    ) {
        PackageManager packageManager = PackageManager.getInstance();
        if (!packageManager.isLoaded(packageId)) {
            sender.sendMessage(Component.translatable(
                    TranslateKeys.Command.USR_PACKAGE_ELEMENTS_PACKAGE_NOT_LOADED
            ).arguments(Component.text(packageId)).color(NamedTextColor.RED));
            return 1;
        }

        // 用户包能注册的类别只有这三类；类型字符串即命令里写的键名
        Registry registry = Registry.getInstance();
        Map<String, DefaultSubtable<? extends GameElement, NamespacedKey>> allCategories = new LinkedHashMap<>();
        allCategories.put("map", registry.category(Categories.MAP));
        allCategories.put("rule", registry.category(Categories.RULE));
        allCategories.put("tag", registry.category(Categories.TAG));

        Map<String, DefaultSubtable<? extends GameElement, NamespacedKey>> selected;
        if (rawType == null || rawType.equals("all")) {
            selected = allCategories;
        } else {
            DefaultSubtable<? extends GameElement, NamespacedKey> subtable = allCategories.get(rawType);
            if (subtable == null) {
                sender.sendMessage(Component.translatable(
                        TranslateKeys.Command.USR_PACKAGE_ELEMENTS_UNKNOWN_TYPE,
                        Component.text(rawType),
                        Component.text(String.join("、", allCategories.keySet()))
                ).color(NamedTextColor.RED));
                return 1;
            }
            selected = Map.of(rawType, subtable);
        }

        sender.sendMessage(Component.translatable(
                TranslateKeys.Command.USR_PACKAGE_ELEMENTS_START, Component.text(packageId)));
        int shown = 0;
        for (Map.Entry<String, DefaultSubtable<? extends GameElement, NamespacedKey>> category
                : selected.entrySet()) {
            for (GameElement element : category.getValue().list()) {
                GameElementMeta meta = element.getElementMeta();
                if (!meta.id().getNamespace().equals(packageId)) continue;
                sender.sendMessage(Component.translatable(TranslateKeys.Command.USR_PACKAGE_ELEMENTS_ENTRY)
                        .arguments(
                                Component.text(category.getKey()),
                                meta.mainName(),
                                Component.text(meta.id().toString())
                        ));
                shown++;
            }
        }
        if (shown == 0) {
            sender.sendMessage(Component.translatable(TranslateKeys.Command.USR_PACKAGE_ELEMENTS_EMPTY));
        }
        sender.sendMessage(Component.translatable(TranslateKeys.Command.USR_PACKAGE_ELEMENTS_END));
        return 1;
    }

    /**
     * 启用一个用户包：先写入启用列表，再按包 Id 定位包根并加载<br>
     * 先写配置是因为包管理器的加载以启用列表包含该包为前提；加载失败时配置保持写入，下次启动仍会尝试
     *
     * @param sender    命令发送者
     * @param packageId 包 Id
     *
     * @return 命令返回值
     * */
    private static int enableUsrPackage(@NonNull CommandSender sender, @NonNull String packageId) {
        PackageManager packageManager = PackageManager.getInstance();
        if (packageManager.isLoaded(packageId)) {
            sender.sendMessage(Component.translatable(
                    TranslateKeys.Command.USR_PACKAGE_ENABLE_ALREADY_LOADED, Component.text(packageId)));
            return 1;
        }

        // 扫描目录，按元文件 Id 定位包根
        PackageRoot target = null;
        for (PackageRoot root : Modules.getModule(PackageLoader.class).scan()) {
            UsrPackage meta = packageManager.readMeta(root).value();
            if (meta != null && meta.id().equals(packageId)) {
                target = root;
                break;
            }
        }
        if (target == null) {
            sender.sendMessage(Component.translatable(
                    TranslateKeys.Command.USR_PACKAGE_ENABLE_PACKAGE_NOT_FOUND, Component.text(packageId)
            ).color(NamedTextColor.RED));
            return 1;
        }

        YamlConfig settings = Config.getInstance().getYmlConfig(PublicFile.GAME_SETTING);
        List<String> enabledPackages = new ArrayList<>(settings.get(GameSettingKey.ENABLE_PACKAGES, List.of()));
        if (!enabledPackages.contains(packageId)) {
            enabledPackages.add(packageId);
            settings.set(GameSettingKey.ENABLE_PACKAGES, enabledPackages);
            try {
                settings.saveAtomic();
            } catch (ConfigException e) {
                // 落盘失败时回滚内存中的列表，避免“内存已启用、配置未写入”的不一致
                enabledPackages.remove(packageId);
                settings.set(GameSettingKey.ENABLE_PACKAGES, enabledPackages);
                Voidairrace.getInstance().getLogger().log(Level.WARNING, ServerLanguage.translate(
                        TranslateKeys.Log.COMMAND_USR_PACKAGE_CONFIG_SAVE_FAILED, e.getMessage()), e);
                sender.sendMessage(Component.translatable(
                        TranslateKeys.Command.USR_PACKAGE_ENABLE_CONFIG_SAVE_FAILED, Component.text(packageId)
                ).color(NamedTextColor.RED));
                return 1;
            }
        }

        PackageManager.PackageLoadResult loadResult = packageManager.load(List.of(target)).getFirst();
        if (!loadResult.isSuccess()) {
            Component message = loadResult.message();
            sender.sendMessage((message == null
                    ? Component.translatable(
                            TranslateKeys.Command.USR_PACKAGE_ENABLE_FAILED, Component.text(packageId))
                    : message).color(NamedTextColor.RED));
            String techMessage = loadResult.techMessage();
            if (techMessage != null) {
                Voidairrace.getInstance().getLogger().warning(ServerLanguage.translate(
                        TranslateKeys.Log.COMMAND_USR_PACKAGE_LOAD_FAILED, packageId, techMessage));
            }
            return 1;
        }

        sender.sendMessage(Component.translatable(
                TranslateKeys.Command.USR_PACKAGE_ENABLE_SUCCESS, Component.text(packageId)));
        return 1;
    }

    /**
     * 禁用一个用户包：先从启用列表移除，再卸载已加载的包<br>
     * 先写配置是为了让“禁用”在下次启动也成立——即便本次卸载失败，包也不会被自动加载
     *
     * @param sender    命令发送者
     * @param packageId 包 Id
     *
     * @return 命令返回值
     * */
    private static int disableUsrPackage(@NonNull CommandSender sender, @NonNull String packageId) {
        PackageManager packageManager = PackageManager.getInstance();

        YamlConfig settings = Config.getInstance().getYmlConfig(PublicFile.GAME_SETTING);
        List<String> enabledPackages = new ArrayList<>(settings.get(GameSettingKey.ENABLE_PACKAGES, List.of()));
        boolean inEnabledList = enabledPackages.removeIf(packageId::equals);
        boolean loaded = packageManager.isLoaded(packageId);
        if (!inEnabledList && !loaded) {
            sender.sendMessage(Component.translatable(
                    TranslateKeys.Command.USR_PACKAGE_DISABLE_NOT_ENABLED, Component.text(packageId)
            ).color(NamedTextColor.RED));
            return 1;
        }

        if (inEnabledList) {
            settings.set(GameSettingKey.ENABLE_PACKAGES, enabledPackages);
            try {
                settings.saveAtomic();
            } catch (ConfigException e) {
                // 落盘失败时回滚内存中的列表，避免“内存已禁用、配置未写入”的不一致
                enabledPackages.add(packageId);
                settings.set(GameSettingKey.ENABLE_PACKAGES, enabledPackages);
                Voidairrace.getInstance().getLogger().log(Level.WARNING, ServerLanguage.translate(
                        TranslateKeys.Log.COMMAND_USR_PACKAGE_CONFIG_SAVE_FAILED, e.getMessage()), e);
                sender.sendMessage(Component.translatable(
                        TranslateKeys.Command.USR_PACKAGE_DISABLE_CONFIG_SAVE_FAILED, Component.text(packageId)
                ).color(NamedTextColor.RED));
                return 1;
            }
        }

        if (loaded) {
            PackageManager.PackageUnloadResult unloadResult = packageManager.unload(List.of(packageId)).getFirst();
            if (!unloadResult.isSuccess()) {
                Component message = unloadResult.message();
                sender.sendMessage((message == null
                        ? Component.translatable(
                                TranslateKeys.Command.USR_PACKAGE_DISABLE_FAILED, Component.text(packageId))
                        : message).color(NamedTextColor.RED));
                String techMessage = unloadResult.techMessage();
                if (techMessage != null) {
                    Voidairrace.getInstance().getLogger().warning(ServerLanguage.translate(
                            TranslateKeys.Log.COMMAND_USR_PACKAGE_UNLOAD_FAILED, packageId, techMessage));
                }
                return 1;
            }
        }

        sender.sendMessage(Component.translatable(
                TranslateKeys.Command.USR_PACKAGE_DISABLE_SUCCESS, Component.text(packageId)));
        return 1;
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
                                                    .getYmlConfig(PublicFile.FLAG)
                                                    .get(FlagKey.MATCH_ABORTED))
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
