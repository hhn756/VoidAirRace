package io.github.hhn756.voidairrace.event;

import io.github.hhn756.voidairrace.core.match.DataKey;
import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.core.match.componentbase.CustomData;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * 当一局比赛开始时触发的事件<br>
 * 监听此事件可以执行赛前准备、广播通知、初始化等操作
 */
public class MatchStartedEvent extends Event {
    /** Bukkit 事件处理器列表 */
    private static final HandlerList HANDLERS = new HandlerList();

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

    /** 开始的比赛对象 */
    private final Match match;

    /** 开始上下文（组件的自定义数据） */
    private final Map<
            @NonNull DataKey<?>,
            CustomData
    > context;

    /**
     * 构造一个比赛开始事件
     *
     * @param match 开始的比赛实例
     * @param context 开始上下文
     */
    public MatchStartedEvent(
            Match match,
            Map<
                    @NonNull DataKey<?>,
                    CustomData
            > context
    ) {
        this.match = match;
        this.context = context;
    }

    /**
     * 获取刚刚开始的比赛
     *
     * @return 比赛实例
     */
    public Match getMatch() {
        return match;
    }

    /**
     * 获取指定组件添加的开始上下文
     *
     * @return 如果指定组件添加了自定义上下文数据将返回它添加的数据，否则返回{@code null}
     * */
    @SuppressWarnings("unchecked")
    public <C extends CustomData> @Nullable C getContext(DataKey<C> key) {
        return (C) context.get(key);
    }
}