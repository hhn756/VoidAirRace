package io.github.hhn756.voidairrace.constants;

import org.bukkit.NamespacedKey;
import org.jspecify.annotations.NonNull;

/**
 * 插件元数据相关的、全局适用的常量
 * */
public class Plugin {
    private Plugin() {}

    /**
     * 蛇形风格、字符串形式的插件命名空间值
     * */
    public static final @NonNull String ns = "void_air_race"; // ns: NameSpace

    /**
     * 目前插件的 Lua API 版本号，用户包元文件的{@code api}字段约束的是它
     * <p>
     * 版本记录：3：文本组件描述符；{@code server.online_players}；{@code player.online} /
     * {@code player.play_state}；比赛句柄补全加入/结束/比赛时间控制/存活查询/玩家与队伍排行。
     * 4：事件列表补全比赛生命周期与玩家事件（含按方块粒度节流的 {@code player_move}）；竞技场与玩家的修改权限边界（只能改自己借的竞技场、自己比赛的参赛者）
     * 5：坐标统一为命名键表 {@code {x=, y=, z=}}（{@code player.position} 返回值、{@code player_move} 载荷与
     * {@code arena.teleport} / {@code arena.clear_chest} 参数）；{@code arena.teleport} 新增可选的朝向参数 {@code {yaw=, pitch=}}
     * <p>
     * 每当用户包可见的插件 API 发生不兼容变更时递增；最小变更单位为一次提交——
     * 外界收不到的内部重构不递增，同一提交内的多次调整合并为一次递增
     * */
    public static final int LUA_API_VERSION = 5;

    /**
     * @param key 指定键（资源名称/路径），仅允许包含小写英文字母{@code a-z}、数字{@code 0-9}和下划线{@code _}
     *
     * @return 一个使用本插件命名空间{@link Plugin#ns}和指定键{@code key}的{@link NamespacedKey}
     * */
    public static @NonNull NamespacedKey key(@NonNull String key) {
        return new NamespacedKey(ns, key);
    }
}
