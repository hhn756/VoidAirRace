package io.github.hhn756.voidairrace.addons.script.api.event;

import net.sandius.rembulan.LuaRuntimeException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 脚本可订阅的引擎事件词表（固定封闭集）<br>
 * 事件名是脚本与引擎的契约：{@code on()} 订阅时按本表校验，未知事件名在订阅点报错，
 * 而不是等到触发时静默丢失——与规则引用的命名空间白名单同一哲学
 * <p>
 * 词表只收低频离散信号；玩家位置这类高频连续量不进事件（逐 tick 触发会压垮
 * 指令预算模型），由脚本经{@code position}查询在 tick 回调里自行轮询
 * */
public enum ScriptEvent {
    /** 玩家退出比赛（淘汰）：回调签名 {@code function(uuid)}，参数为玩家 UUID 字符串 */
    PLAYER_LEAVE_MATCH("player_leave_match"),
    /** 比赛结束：回调签名 {@code function()}，无参数 */
    MATCH_OVER("match_over");

    /** 脚本侧事件名（小写下划线风格） */
    private final String eventName;

    ScriptEvent(@NonNull String eventName) {
        this.eventName = eventName;
    }

    /**
     * @return 脚本侧事件名
     * */
    public @NonNull String eventName() {
        return eventName;
    }

    /**
     * 按脚本侧事件名解析词表项
     *
     * @param eventName 脚本侧事件名
     *
     * @return 对应词表项，未知事件名返回{@code null}
     * */
    public static @Nullable ScriptEvent byName(@NonNull String eventName) {
        for (ScriptEvent event : values()) {
            if (event.eventName.equals(eventName)) return event;
        }
        return null;
    }

    /**
     * 按脚本侧事件名解析词表项，未知事件名抛出带可用词表提示的 Lua 错误
     *
     * @param eventName 脚本侧事件名（原样回显在错误消息里）
     * */
    public static @NonNull ScriptEvent require(@NonNull String eventName) {
        ScriptEvent event = byName(eventName);
        if (event == null) {
            String known = Arrays.stream(values())
                    .map(ScriptEvent::eventName)
                    .collect(Collectors.joining("、"));
            throw new LuaRuntimeException(
                    "未知事件：\"" + eventName + "\"（可用事件：" + known + "）");
        }
        return event;
    }
}
