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
        api.dispatch(ScriptEvent.MATCH_START);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMatchStatusChanged(@NonNull MatchStatusChangedEvent event) {
        api.dispatch(ScriptEvent.MATCH_STATE_CHANGED, ByteString.of(event.getMatch().state().name()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoinMatch(@NonNull PlayerJoinMatchEvent event) {
        api.dispatch(ScriptEvent.PLAYER_JOIN_MATCH, uuid(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerLeaveMatch(@NonNull PlayerLeaveMatchEvent event) {
        api.dispatch(ScriptEvent.PLAYER_LEAVE_MATCH, uuid(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMatchOver(@NonNull MatchOverEvent event) {
        api.dispatch(ScriptEvent.MATCH_OVER);
    }

    /** 玩家 UUID → Lua 字符串（事件载荷统一用值） */
    private static @NonNull ByteString uuid(@NonNull Player player) {
        return ByteString.of(player.getUniqueId().toString());
    }
}
