package io.github.hhn756.voidairrace.core.playerstatemanager;

import io.github.hhn756.voidairrace.infrastructure.listenerregistrar.AutoRegistration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * 玩家状态管理器的事件监听器
 * */
@AutoRegistration
public class EventListener implements Listener {
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        PlayerInitializer.getInstance().initializePlayer(event.getPlayer());
    }
}
