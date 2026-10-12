package io.github.hhn756.voidairrace.playerinteraction.audiovisualservices;

import io.github.hhn756.voidairrace.constants.TranslateKeys;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * 用于向玩家显示加入服务器时的提示消息
 * */
public class LoginMessage implements Listener {
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // 1. 取消默认的加入广播消息
        event.joinMessage(null);

        // 2. 给新加入的玩家自己发送专属消息
        player.sendMessage(
                Component.translatable(TranslateKeys.AudioVisualServices.LOGIN_MESSAGE_LINE1_SELF)
                        .arguments(Component.text(player.getName()))
        );

        // 3. 给其他所有玩家发送广播消息
        Component broadcastMsg = Component.translatable(
                TranslateKeys.AudioVisualServices.LOGIN_MESSAGE_LINE1
        ).arguments(Component.text(player.getName()));

        for (Player online : player.getServer().getOnlinePlayers()) {
            if (!online.equals(player)) {
                online.sendMessage(broadcastMsg);
            }
        }

        // 4. 音效只给新玩家播放
        player.playSound(Sound.sound(
                org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
                Sound.Source.MASTER,
                1.0f,
                1.0f
        ));
    }
}
