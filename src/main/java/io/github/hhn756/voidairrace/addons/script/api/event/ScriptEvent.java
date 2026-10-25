package io.github.hhn756.voidairrace.addons.script.api.event;

import net.sandius.rembulan.LuaRuntimeException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 脚本可订阅的引擎事件（固定集合）<br>
 * 事件名是脚本与引擎的约定：{@code on()} 订阅时按本表校验，未知事件名在订阅点报错，
 * 而不是等到事件发生时静默丢弃
 * <p>
 * 本表只收离散事件；位置这一类只有{@link #PLAYER_MOVE}一项，按方块粒度合并、
 * 每玩家限速派发（不按原始移动包逐条触发，否则会耗尽指令预算）。
 * 需要更细的位置可经{@code position}查询在 tick 回调里自行轮询。
 * 参数只含值（UUID 字符串、状态名、坐标数字），不暴露事件对象或比赛对象——
 * 脚本没有取消或改写事件的入口
 * */
public enum ScriptEvent {
    /** 玩家加入比赛：回调签名 {@code function(uuid)}；比赛开始不会为初始参赛者补发 */
    PLAYER_JOIN_MATCH("player_join_match"),
    /** 玩家退出比赛（淘汰/离场）：回调签名 {@code function(uuid)} */
    PLAYER_LEAVE_MATCH("player_leave_match"),
    /** 比赛开始：回调签名 {@code function()}，无参数 */
    MATCH_START("match_start"),
    /** 比赛状态变化（不含开始与结束）：回调签名 {@code function(state_name)} */
    MATCH_STATE_CHANGED("match_state_changed"),
    /** 比赛结束：回调签名 {@code function()}，无参数 */
    MATCH_OVER("match_over"),
    /** 玩家加入服务器：回调签名 {@code function(uuid)} */
    PLAYER_JOIN("player_join"),
    /** 玩家离开服务器：回调签名 {@code function(uuid)} */
    PLAYER_QUIT("player_quit"),
    /** 玩家死亡：回调签名 {@code function(uuid, killer_uuid_or_nil)} */
    PLAYER_DEATH("player_death"),
    /** 玩家重生：回调签名 {@code function(uuid)} */
    PLAYER_RESPAWN("player_respawn"),
    /**
     * 玩家移动：回调签名 {@code function(uuid, x, y, z)}<br>
     * 只在跨方块时派发（同一方块内的走动与转头不触发），同一玩家每 50ms 至多一次；
     * 坐标是派发时的落点，不保证覆盖途经的每个方块
     * */
    PLAYER_MOVE("player_move");

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
     * 按脚本侧事件名查找对应事件
     *
     * @param eventName 脚本侧事件名
     *
     * @return 对应事件，未知事件名返回{@code null}
     * */
    public static @Nullable ScriptEvent byName(@NonNull String eventName) {
        for (ScriptEvent event : values()) {
            if (event.eventName.equals(eventName)) return event;
        }
        return null;
    }

    /**
     * 按脚本侧事件名查找对应事件，未知事件名抛出带可用事件名列表的 Lua 错误
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
