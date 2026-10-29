package io.github.hhn756.voidairrace.addons.script.api.event;

import io.github.hhn756.voidairrace.event.*;
import net.sandius.rembulan.ByteString;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jspecify.annotations.NonNull;

/**
 * 比赛域引擎事件 → 本包订阅者的桥接监听器<br>
 * 只从事件取裸值（UUID 字符串、状态名）交给脚本，不交事件对象或比赛对象
 * */
final class MatchEventBridge implements Listener {
    /** 派发目标 */
    private final @NonNull EventApi api;

    MatchEventBridge(@NonNull EventApi api) {
        this.api = api;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMatchStarted(@NonNull MatchStartedEvent event) {
        api.dispatch(ScriptEvent.MATCH_START, event.getMatch(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMatchTimeExpired(@NonNull MatchTimeExpiredEvent event) {
        api.dispatch(ScriptEvent.MATCH_TIME_EXPIRED, event.getMatch(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoinMatch(@NonNull PlayerJoinMatchEvent event) {
        api.dispatch(ScriptEvent.PLAYER_JOIN_MATCH, event.getMatch(), false, uuid(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerLeaveMatch(@NonNull PlayerLeaveMatchEvent event) {
        api.dispatch(ScriptEvent.PLAYER_LEAVE_MATCH, event.getMatch(), false, uuid(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMatchOver(@NonNull MatchOverEvent event) {
        // 收尾阶段：比赛已进入 ENDING，句柄只放行查询
        api.dispatch(ScriptEvent.MATCH_OVER, event.getMatch(), true);
    }

    /** 玩家 UUID → Lua 字符串（事件载荷统一用值） */
    private static @NonNull ByteString uuid(@NonNull Player player) {
        return ByteString.of(player.getUniqueId().toString());
    }
}
