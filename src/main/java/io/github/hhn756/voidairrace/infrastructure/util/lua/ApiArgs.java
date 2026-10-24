package io.github.hhn756.voidairrace.infrastructure.util.lua;

import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.LuaRuntimeException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Lua 参数校验工具
 * */
public final class ApiArgs {
    /** 静态工具类，不可实例化 */
    private ApiArgs() {}

    /**
     * 校验参数为 Lua 字符串并转为 Java 字符串
     *
     * @param arg  参数原值
     * @param what 参数用途说明，用于错误消息（最终消息为{@code what + "必须是字符串"}）
     * */
    public static @NonNull String expectString(@Nullable Object arg, @NonNull String what) {
        if (arg instanceof ByteString bs) return bs.decode();
        throw new LuaRuntimeException(what + "必须是字符串");
    }

    /**
     * 校验参数为 Lua 数字并转为 Java 双精度浮点数<br>
     * Lua 数字不分整型/浮点型，整数值统一接受
     *
     * @param arg  参数原值
     * @param what 参数用途说明，用于错误消息（最终消息为{@code what + "必须是数字"}）
     * */
    public static double expectDouble(@Nullable Object arg, @NonNull String what) {
        if (arg instanceof Long l) return l.doubleValue();
        if (arg instanceof Double d) return d;
        throw new LuaRuntimeException(what + "必须是数字");
    }
}
