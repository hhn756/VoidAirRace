package io.github.hhn756.voidairrace.playerinteraction.audiovisualservices;

import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.infrastructure.util.schedulingutil.SchedulingUtil;
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
    /**
     * 给自己那条欢迎语的延迟（tick）<br>
     * 客户端把已缓存的服务器资源包与区块加载一并应用，加入瞬间发出的自定义翻译键会先被渲染成键名原文；
     * 等客户端的语言表就绪后再发，其余消息与广播不涉及该时序，保持即时
     * */
    private static final long SELF_WELCOME_DELAY_TICKS = 60L;

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // 1. 取消默认的加入广播消息
        event.joinMessage(null);

        // 2. 给其他所有玩家发送广播消息（他们的资源包早已生效，不受语言表时序影响）
        Component broadcastMsg = Component.translatable(
                TranslateKeys.AudioVisualServices.LOGIN_MESSAGE_LINE1
        ).arguments(Component.text(player.getName()));

        for (Player online : player.getServer().getOnlinePlayers()) {
            if (!online.equals(player)) {
                online.sendMessage(broadcastMsg);
            }
        }

        // 3. 给新加入的玩家自己发送专属消息
        SchedulingUtil.runOnMainThreadLater(() -> {
            if (player.isOnline()) {
                player.sendMessage(
                        Component.translatable(TranslateKeys.AudioVisualServices.LOGIN_MESSAGE_LINE1_SELF)
                                .arguments(Component.text(player.getName()))
                );
            }
        }, SELF_WELCOME_DELAY_TICKS);

        // 4. 音效只给新玩家播放
        player.playSound(Sound.sound(
                org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
                Sound.Source.MASTER,
                1.0f,
                1.0f
        ));
    }
}
