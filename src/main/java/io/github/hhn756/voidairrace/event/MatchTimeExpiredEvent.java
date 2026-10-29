package io.github.hhn756.voidairrace.event;

import io.github.hhn756.voidairrace.core.match.Match;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NonNull;

/**
 * 比赛剩余时间归零时触发一次：只从大于 0 变为 0 的那一刻派发，保持 0 不再重复<br>
 * 外部通过 {@code setRemaining(0)} 把时间直接设为 0 同样触发
 * */
public class MatchTimeExpiredEvent extends Event {
    /** Bukkit 事件处理器列表 */
    private static final HandlerList HANDLERS = new HandlerList();

    /** 剩余时间归零的比赛 */
    private final @NonNull Match match;

    public MatchTimeExpiredEvent(@NonNull Match match) {
        this.match = match;
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
     * @return 剩余时间归零的比赛
     * */
    public @NonNull Match getMatch() {
        return match;
    }
}
