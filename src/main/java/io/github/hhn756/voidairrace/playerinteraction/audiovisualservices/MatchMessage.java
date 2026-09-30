package io.github.hhn756.voidairrace.playerinteraction.audiovisualservices;

import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.map.MapComp;
import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.core.match.basecomponents.contestant.ContestantComp;
import io.github.hhn756.voidairrace.core.match.basecomponents.gametime.GameTimeComp;
import io.github.hhn756.voidairrace.event.MatchOverEvent;
import io.github.hhn756.voidairrace.event.MatchStartedEvent;
import io.github.hhn756.voidairrace.infrastructure.listenerregistrar.AutoRegistration;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * 显示比赛相关的提示消息和音效
 * */
@AutoRegistration
public class MatchMessage implements Listener {

    @EventHandler(priority = EventPriority.HIGH) // 防止音效播放后玩家被tp进场导致只响一下
    public void onMatchStarted(MatchStartedEvent event) {
        Server server = Bukkit.getServer();
        Match match = event.getMatch();

        // 比赛 开始 时显示提示消息和音效
        server.broadcast(
                Component.translatable(TranslateKeys.AudioVisualServices.MATCH_MESSAGE_MATCH_STARTED_LINE1)
        );
        server.broadcast(
                Component.translatable(TranslateKeys.AudioVisualServices.MATCH_MESSAGE_MATCH_STARTED_LINE2)
                        .arguments(match.configOf(MapComp.CONFIG_KEY).map().getElementMeta().mainName())
        );
        server.broadcast(
                Component.translatable(TranslateKeys.AudioVisualServices.MATCH_MESSAGE_MATCH_STARTED_LINE3)
                        .arguments(Component.text(match.configOf(GameTimeComp.CONFIG_KEY).duration() / 20d))
        );
        server.broadcast(
                Component.translatable(TranslateKeys.AudioVisualServices.MATCH_MESSAGE_MATCH_STARTED_LINE4)
        );

        Sound sound = Sound.sound(
                org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
                Sound.Source.MASTER,
                1.0f,
                1.0f
        );
        event.getMatch().configOf(ContestantComp.CONFIG_KEY)
                .initialContestants().forEach(contestant -> contestant.playSound(sound));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMatchOver(MatchOverEvent event) {
        Server server = Bukkit.getServer();

        // 比赛 结束 时显示提示消息和音效
        server.broadcast(
                Component.translatable(TranslateKeys.AudioVisualServices.MATCH_MESSAGE_MATCH_OVER_LINE1)
        );

        Sound sound = Sound.sound(
                org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
                Sound.Source.MASTER,
                1.0f,
                1.0f
        );
        event.getMatch()
                .configOf(ContestantComp.CONFIG_KEY)
                .initialContestants()
                .forEach(
                        contestant -> contestant.playSound(sound)
                );
    }
}
