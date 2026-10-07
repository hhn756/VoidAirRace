package io.github.hhn756.voidairrace.constants;

import org.bukkit.NamespacedKey;
import org.jspecify.annotations.NonNull;

/**
 * 插件元数据相关的常量
 * */
public class Plugin {
    private Plugin() {}

    /** 蛇形风格、字符串形式的插件命名空间 */
    public static final @NonNull String ns = "void_air_race";

    /**
     * 目前插件的 Lua API 版本号，用户包元文件的{@code api}字段约束的是它<br>
     * 每当用户包可见的插件 API 发生不兼容变更时递增
     * */
    public static final int API_VERSION = 1;

    /**
     * @param key 指定键（资源名称/路径），仅允许包含小写英文字母{@code a-z}、数字{@code 0-9}和下划线{@code _}
     *
     * @return 一个使用本插件命名空间（{@link Plugin#ns}）和指定{@code key}键的{@link NamespacedKey}
     * */
    public static @NonNull NamespacedKey key(@NonNull String key) {
        return new NamespacedKey(ns, key);
    }
}
