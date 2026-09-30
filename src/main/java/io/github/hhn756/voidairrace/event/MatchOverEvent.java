package io.github.hhn756.voidairrace.event;

import io.github.hhn756.voidairrace.core.match.DataKey;
import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.core.match.componentbase.CustomData;
import io.github.hhn756.voidairrace.core.match.componentbase.MatchComp;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * 当一局比赛结束时触发的事件<br>
 * 该事件由 {@link io.github.hhn756.voidairrace.core.match.MatchCoordinator} 在调用 {@code stopMatch} 方法后发布<br>
 * 监听此事件可以执行赛后清理、奖励发放、数据统计等操作
 */
public class MatchOverEvent extends Event {
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

    /** 刚刚结束的比赛 */
    private final Match match;

    /** 结束上下文（组件的自定义数据） */
    private final Map<
            @NonNull DataKey<?>,
            CustomData
    > context;

    /**
     * 构造一个比赛结束事件
     *
     * @param match 结束的比赛实例
     */
    public MatchOverEvent(
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
     * 获取刚刚结束的比赛
     *
     * @return 比赛实例
     */
    public Match getMatch() {
        return match;
    }

    /**
     * 获取指定组件添加的结束上下文
     *
     * @param component 将返回指定组件所添加的上下文数据
     * @param dataType 需指定{@code component}所指定组件所添加的自定义数据类型
     *
     * @return 如果指定组件添加了自定义上下文数据将返回它添加的数据，否则返回{@code null}
     * */
    public <C extends CustomData> @Nullable C getContext(
            @NonNull Class<? extends MatchComp> component,
            Class<C> dataType
    ) {
        CustomData customConfig = context.get(component);
        if (dataType.isInstance(customConfig)) {
            @SuppressWarnings("unchecked")
            C result = (C) customConfig;
            return result;
        }
        return null;
    }
}