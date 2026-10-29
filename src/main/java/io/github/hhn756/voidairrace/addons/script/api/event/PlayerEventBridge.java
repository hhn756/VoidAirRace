package io.github.hhn756.voidairrace.addons.script.api.event;

import io.github.hhn756.voidairrace.addons.script.simplevalue.Position;
import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.core.match.MatchCoordinator;
import net.sandius.rembulan.ByteString;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家域 Bukkit 事件 → 本包订阅者的桥接监听器<br>
 * 只在{@link EventPriority#MONITOR}观察，不做取消与改写；死亡事件把击杀者作为
 * 可空第二个参数传出
 * <p>
 * 移动事件是唯一按位置触发的事件：{@link PlayerMoveEvent} 按移动包逐条触发，
 * 原样转发会耗尽指令预算，故在桥接层做两道过滤——同方块内的位移与转头
 * 不派发、同一玩家两次派发至少间隔{@link #MOVE_INTERVAL_MS}；无人订阅时连
 * 方块比较都省掉
 * */
final class PlayerEventBridge implements Listener {
    /** 同一玩家两次移动派发的最小间隔（毫秒）约合每秒 20 次，与服务器 tick 速率对齐 */
    private static final long MOVE_INTERVAL_MS = 50L;

    /** 派发目标 */
    private final @NonNull EventApi api;

    /** 每个玩家上次派发移动事件的时刻（毫秒）；玩家退出时移除 */
    private final @NonNull Map<@NonNull UUID, @NonNull Long> lastMoveAt = new HashMap<>();

    PlayerEventBridge(@NonNull EventApi api) {
        this.api = api;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NonNull PlayerJoinEvent event) {
        api.dispatch(ScriptEvent.PLAYER_JOIN, currentMatch(), true, uuid(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NonNull PlayerQuitEvent event) {
        Player player = event.getPlayer();
        lastMoveAt.remove(player.getUniqueId());
        api.dispatch(ScriptEvent.PLAYER_QUIT, currentMatch(), true, uuid(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(@NonNull PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        api.dispatch(ScriptEvent.PLAYER_DEATH, currentMatch(), true,
                uuid(victim), killer == null ? null : uuid(killer));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(@NonNull PlayerRespawnEvent event) {
        api.dispatch(ScriptEvent.PLAYER_RESPAWN, currentMatch(), true, uuid(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(@NonNull PlayerMoveEvent event) {
        // 被取消的移动没有发生；无人订阅时不做任何计算
        if (event.isCancelled() || !api.hasSubscribers(ScriptEvent.PLAYER_MOVE)) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        // 方块粒度：同一方块内的走动与转头不算移动
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()
                && from.getWorld() == to.getWorld()) {
            return;
        }

        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Long last = lastMoveAt.get(player.getUniqueId());
        if (last != null && now - last < MOVE_INTERVAL_MS) return;
        lastMoveAt.put(player.getUniqueId(), now);

        api.dispatch(ScriptEvent.PLAYER_MOVE, currentMatch(), true, uuid(player),
                Position.toLua(api.stateContext(),
                        new Position(to.getX(), to.getY(), to.getZ())));
    }

    /** 当前比赛（无比赛时为 {@code null}）：玩家事件不属于某一场比赛，只取当前那一场作为上下文 */
    private static @Nullable Match currentMatch() {
        return MatchCoordinator.getInstance().getCurrentMatch();
    }

    /** 玩家 UUID → Lua 字符串（事件载荷统一用值） */
    private static @NonNull ByteString uuid(@NonNull Player player) {
        return ByteString.of(player.getUniqueId().toString());
    }
}
