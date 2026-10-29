package io.github.hhn756.voidairrace.event;

import io.github.hhn756.voidairrace.core.match.Match;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NonNull;

/**
 * 比赛剩余时间发生变化时触发：倒计时随 tick 递减，或外部通过 {@code setRemaining} 修改<br>
 * 变化前后的值由{@link #getOldRemaining()}与{@link #getNewRemaining()}给出，订阅者不需要自己回查；
 * 本事件在倒计时期间每秒约 20 次，订阅者应保持轻量。归零时刻另有{@link MatchTimeExpiredEvent}
 * */
public class MatchRemainingChangedEvent extends Event {
    /** Bukkit 事件处理器列表 */
    private static final HandlerList HANDLERS = new HandlerList();

    /** 剩余时间发生变化的比赛 */
    private final @NonNull Match match;

    /** 变化前的剩余 tick 数 */
    private final int oldRemaining;

    /** 变化后的剩余 tick 数 */
    private final int newRemaining;

    public MatchRemainingChangedEvent(
            @NonNull Match match,
            int oldRemaining,
            int newRemaining
    ) {
        this.match = match;
        this.oldRemaining = oldRemaining;
        this.newRemaining = newRemaining;
    }

    /**
     * 获取 Bukkit 事件处理器列表（静态方法）
     *
     * @return 处理器列表
     */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public @NonNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * @return 剩余时间发生变化的比赛
     * */
    public @NonNull Match getMatch() {
        return match;
    }

    /**
     * @return 变化前的剩余 tick 数
     * */
    public int getOldRemaining() {
        return oldRemaining;
    }

    /**
     * @return 变化后的剩余 tick 数
     * */
    public int getNewRemaining() {
        return newRemaining;
    }
}
