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
     * 每当用户包可见的插件 API 面发生变化（新增或破坏）时递增；最小变更单位为一次提交——
     * 外界收不到的内部重构不递增，同一提交内的多次调整合并为一次递增<br>
     * 每次修改前检查上次提交的API版本，防止误递增
     * */
    public static final int LUA_API_VERSION = 6;

    /**
     * @param key 指定键（资源名称/路径），仅允许包含小写英文字母{@code a-z}、数字{@code 0-9}和下划线{@code _}
     *
     * @return 一个使用本插件命名空间{@link Plugin#ns}和指定键{@code key}的{@link NamespacedKey}
     * */
    public static @NonNull NamespacedKey key(@NonNull String key) {
        return new NamespacedKey(ns, key);
    }
}
