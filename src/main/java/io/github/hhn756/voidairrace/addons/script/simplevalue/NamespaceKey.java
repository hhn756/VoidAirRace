package io.github.hhn756.voidairrace.addons.script.simplevalue;

import net.kyori.adventure.key.Key;
import net.sandius.rembulan.LuaRuntimeException;
import org.jspecify.annotations.NonNull;

/**
 * 命名空间键值：Lua 字符串 {@code "namespace:path"} ↔ Adventure {@link Key}<br>
 * 只做单向解析，不做反向输出
 * */
public final class NamespaceKey {
    /** 静态工具类，不可实例化 */
    private NamespaceKey() {}

    /**
     * 把命名空间键字符串解析为 Adventure {@link Key}
     *
     * @param value 键字符串（{@code namespace:path} 形式）
     * @param what  参数用途说明，用于错误消息
     *
     * @return 解析后的键
     * */
    public static @NonNull Key expect(@NonNull String value, @NonNull String what) {
        try {
            return Key.key(value);
        } catch (Exception e) {
            throw new LuaRuntimeException(what + "不是合法的命名空间键：\"" + value + "\"（" + e.getMessage() + "）");
        }
    }
}
