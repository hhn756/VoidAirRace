package io.github.hhn756.voidairrace.core.matchrule.generalrules;

import io.github.hhn756.voidairrace.constants.Plugin;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.addons.GameElementMeta;
import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.core.match.MatchCoordinator;
import io.github.hhn756.voidairrace.core.match.basecomponents.contestant.ContestantComp;
import io.github.hhn756.voidairrace.core.match.basecomponents.gametime.GameTimeComp;
import io.github.hhn756.voidairrace.core.matchrule.MatchRule;
import io.github.hhn756.voidairrace.event.MatchStatusChangedEvent;
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
    public void onMatchStatusChanged(MatchStatusChangedEvent event) {
        Match match = event.getMatch();
        // 只剩一队 <- 这个优先检测，应对时间归零同刻击败玩家
        if (match.comp(ContestantComp.class).survivingTeamCount() <= 1) {
            MatchCoordinator.getInstance().stopMatch();
        }
        // 时间归零
        if (match.comp(GameTimeComp.class).getRemaining() <= 0) {
            MatchCoordinator.getInstance().stopMatch();
            return;
        }
    }

    @Override
    public @NonNull GameElementMeta getElementMeta() {
        return meta;
    }
}
