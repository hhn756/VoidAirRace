package io.github.hhn756.voidairrace.playerinteraction.audiovisualservices;

import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.core.match.MatchCoordinator;
import io.github.hhn756.voidairrace.core.match.basecomponents.gametime.GameTimeComp;
import io.github.hhn756.voidairrace.event.MatchOverEvent;
import io.github.hhn756.voidairrace.event.MatchStartedEvent;
import io.github.hhn756.voidairrace.event.MatchStatusChangedEvent;
import io.github.hhn756.voidairrace.infrastructure.listenerregistrar.AutoRegistration;
import io.github.hhn756.voidairrace.infrastructure.util.Percentage;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * 管理比赛内用于显示剩余游戏时间的bossbar
 * */
@AutoRegistration
public class GameTimeBossbar implements Listener {
    BossBar bossbar;

    public GameTimeBossbar() {
        // 初始化用于显示剩余比赛时间的bossbar
        bossbar = BossBar.bossBar(
                Component.translatable(TranslateKeys.AudioVisualServices.GAME_TIME_BOSSBAR_BOSSBAR_NAME)
                        .arguments(Component.text(0)),
                1.0f,
                BossBar.Color.BLUE,
                BossBar.Overlay.PROGRESS
        );
    }

    @EventHandler
    public void onMatchStarted(MatchStartedEvent event) {
        // 比赛开始时向所有在线玩家显示剩余游戏时间
        Bukkit.getServer().getOnlinePlayers().forEach(
                player -> player.showBossBar(bossbar)
        );
        updateBossbar(event.getMatch());
    }

    @EventHandler
    public void onMatchOver(MatchOverEvent event) {
        // 比赛结束时清空显示
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.hideBossBar(bossbar);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // 如果比赛正在进行中，向新加入服务器的玩家显示剩余时间，否则取消显示
        if (MatchCoordinator.getInstance().matchIsRunning()) {
            player.showBossBar(bossbar);
        } else {
            player.hideBossBar(bossbar);
        }
    }

    @EventHandler
    public void onMatchStatusChanged(MatchStatusChangedEvent event) {
        // bossbar值随比赛剩余时间更新
        updateBossbar(event.getMatch());
    }

    /**
     * 读取当前系统状态，用此数据更新剩余游戏时间bossbar
     *
     * @param match 从此比赛中读取初始比赛时间和剩余比赛时间
     * */
    private void updateBossbar(Match match) {
        int remainingTime = match.comp(GameTimeComp.class).getRemaining();
        bossbar.name(
                Component.translatable(TranslateKeys.AudioVisualServices.GAME_TIME_BOSSBAR_BOSSBAR_NAME)
                        .arguments(Component.text(remainingTime / 20))
        );
        bossbar.progress(
                Percentage.toPercentage(
                        match.configOf(GameTimeComp.CONFIG_KEY).duration(),
                        remainingTime
                )
        );
    }
}
