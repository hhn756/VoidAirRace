package io.github.hhn756.voidairrace.playerinteraction.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.hhn756.voidairrace.constants.PermissionNode;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.playerstatemanager.PlayerInitializer;
import io.github.hhn756.voidairrace.infrastructure.listenerregistrar.AutoRegistration;
import io.github.hhn756.voidairrace.infrastructure.modules.BootstrapStage;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

/**
 * 玩家管理命令
 * */
@AutoRegistration
public class PlayerManagerCommand implements BootstrapStage {
    public void onBootstrap(@NonNull BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commandsEvent -> {
            LiteralCommandNode<CommandSourceStack> node = Commands.literal("player_manager")
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
                    ).then(Commands.literal("state")
                            .then(Commands.literal("get")
                                    .then(Commands.argument("state_system", StringArgumentType.string())
                                            .executes(ctx -> {
                                                if (!(ctx.getSource().getSender() instanceof Player)) return 1;

                                                Player player = (Player) ctx.getSource().getSender();

                                                return 1;
                                            })
                                            .then(Commands.argument("player", StringArgumentType.string())
                                                    .executes(ctx -> {
                                                        if (!(ctx.getSource().getSender() instanceof Player)) return 1;

                                                        Player player = (Player) ctx.getSource().getSender();

                                                        return 1;
                                                    })
                                            )
                                    )
                            )
                    )
                    .build();
            commandsEvent.registrar().register(node);
        });
    }
}
