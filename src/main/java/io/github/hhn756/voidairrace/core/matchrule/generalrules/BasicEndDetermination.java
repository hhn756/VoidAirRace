package io.github.hhn756.voidairrace.core.matchrule.generalrules;

import io.github.hhn756.voidairrace.addons.GameElementMeta;
import io.github.hhn756.voidairrace.constants.Plugin;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.core.match.MatchCoordinator;
import io.github.hhn756.voidairrace.core.match.component.contestant.ContestantComp;
import io.github.hhn756.voidairrace.core.matchrule.MatchRule;
import io.github.hhn756.voidairrace.event.MatchTimeExpiredEvent;
import io.github.hhn756.voidairrace.event.PlayerJoinMatchEvent;
import io.github.hhn756.voidairrace.event.PlayerLeaveMatchEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * 在比赛时间小于等于0或存活玩家不足时结束游戏
 * */
public class BasicEndDetermination implements MatchRule, Listener {
    public static final NamespacedKey ID = Plugin.key("basic_end_determination");
    public static final GameElementMeta meta = new GameElementMeta(
            ID,
            List.of(Component.translatable(
                    TranslateKeys.MatchRule.BASIC_END_DETERMINATION_NAME)),
            List.of(Component.translatable(
                    TranslateKeys.MatchRule.BASIC_END_DETERMINATION_DESCRIPTION)),
            List.of(Component.translatable(
                    TranslateKeys.MatchRule.BASIC_END_DETERMINATION_AUTHOR)),
            Component.translatable(
                    TranslateKeys.MatchRule.BASIC_END_DETERMINATION_DISPLAY_VERSION),
            1L,
            List.of(),
            List.of()
    );

    @EventHandler
    public void onPlayerJoinMatch(@NonNull PlayerJoinMatchEvent event) {
        checkSurvivingTeams(event.getMatch());
    }

    @EventHandler
    public void onPlayerLeaveMatch(@NonNull PlayerLeaveMatchEvent event) {
        checkSurvivingTeams(event.getMatch());
    }

    @EventHandler
    public void onMatchTimeExpired(@NonNull MatchTimeExpiredEvent event) {
        // 时间归零：比赛结束
        MatchCoordinator.getInstance().stopMatch();
    }

    /**
     * 参赛者变化后检查存活队伍数：只剩一队即结束比赛
     * */
    private void checkSurvivingTeams(@NonNull Match match) {
        if (match.comp(ContestantComp.class).survivingTeamCount() <= 1) {
            MatchCoordinator.getInstance().stopMatch();
        }
    }

    @Override
    public @NonNull GameElementMeta getElementMeta() {
        return meta;
    }
}
