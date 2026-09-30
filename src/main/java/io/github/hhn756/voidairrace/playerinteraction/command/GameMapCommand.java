package io.github.hhn756.voidairrace.playerinteraction.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.hhn756.voidairrace.constants.Categories;
import io.github.hhn756.voidairrace.constants.PermissionNode;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.map.GameMap;
import io.github.hhn756.voidairrace.core.map.MapEntry;
import io.github.hhn756.voidairrace.core.map.MapInitializer;
import io.github.hhn756.voidairrace.core.map.PlayableGameMap;
import io.github.hhn756.voidairrace.infrastructure.modules.BootstrapStage;
import io.github.hhn756.voidairrace.infrastructure.registry.Registry;
import io.github.hhn756.voidairrace.infrastructure.util.schedulingutil.SchedulingUtil;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;

import java.util.Collection;

/**
 * 游戏地图管理命令
 * */
public class GameMapCommand implements BootstrapStage {
    public void onBootstrap(@NonNull BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commandsEvent -> {
            LiteralCommandNode<CommandSourceStack> node = Commands.literal("game_map")
                    .requires(ctx -> ctx.getSender().hasPermission(
                            PermissionNode.GAME_MAP_COMMAND.toString())
                    )
                    .then(Commands.literal("list")
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
                    )
                    .build();
            commandsEvent.registrar().register(node);
        });
    }
}
