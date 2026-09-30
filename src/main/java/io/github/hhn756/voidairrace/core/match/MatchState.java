package io.github.hhn756.voidairrace.core.match;

import io.github.hhn756.voidairrace.core.match.componentbase.CustomData;

/**
 * 包含所有比赛状态的枚举
 */
public enum MatchState {
    /**
     * 代表等待游戏中，随时可开始：
     * <ul>
     *     <li>{@link Match}外部不可访问除{@link Match#start(CustomData...)}外的所有成员</li>
     * </ul>
     * */
    SCHEDULED,

    /**
     * 代表比赛正在尝试开始（启动）：
     * <ul>
     *     <li>执行配置验证、准备资源/数据等操作</li>
     *     <li>除与比赛绑定的部分外不能访问比赛实例，以免NPE或未完全构造的对象</li>
     * </ul>
     * */
    STARTING,

    /**
     * 代表比赛正在进行中：
     * <ul>
     *     <li>已准备好比赛相关资源、数据，随时可访问</li>
     *     <li>玩家正在进行游戏，比赛相关状态不断变化</li>
     * </ul>
     * */
    IN_PROGRESS,

    /**
     * 代表比赛正在结束中：
     * <ul>
     *     <li>执行清理资源、发布事件等操作</li>
     *     <li>除与比赛绑定的部分外不能访问比赛实例，以免访问已停止运行的机制</li>
     * </ul>
     * */
    ENDING
}
