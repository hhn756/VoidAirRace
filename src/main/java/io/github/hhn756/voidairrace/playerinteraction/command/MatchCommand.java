package io.github.hhn756.voidairrace.playerinteraction.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.constants.Categories;
import io.github.hhn756.voidairrace.constants.PermissionNode;
import io.github.hhn756.voidairrace.constants.Plugin;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.map.MapEntry;
import io.github.hhn756.voidairrace.core.match.MatchCoordinator;
import io.github.hhn756.voidairrace.infrastructure.config.Config;
import io.github.hhn756.voidairrace.infrastructure.config.files.GameSettingKeys;
import io.github.hhn756.voidairrace.infrastructure.config.files.PublicFiles;
import io.github.hhn756.voidairrace.infrastructure.listenerregistrar.AutoRegistration;
import io.github.hhn756.voidairrace.infrastructure.modules.BootstrapStage;
import io.github.hhn756.voidairrace.infrastructure.registry.DefaultSubtable;
import io.github.hhn756.voidairrace.infrastructure.registry.Registry;
import io.github.hhn756.voidairrace.result.OperationResult;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;

/**
 * 比赛管理命令
 * */
@AutoRegistration
public class MatchCommand implements BootstrapStage {
    public void onBootstrap(@NonNull BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commandsEvent -> {
            LiteralCommandNode<CommandSourceStack> node = Commands.literal("match")
                    .requires(
                            ctx -> ctx.getSender().hasPermission(PermissionNode.MATCH_COMMAND.toString())
                    )
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
                    ).then(Commands.literal("set_map")
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
                    )
                    .then(Commands.literal("get_map")
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
            commandsEvent.registrar().register(node);
        });
    }
}
