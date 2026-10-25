package io.github.hhn756.voidairrace.infrastructure.util.lua;

import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.LuaRuntimeException;
import net.sandius.rembulan.Table;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Lua 参数与表字段校验工具：参数类型收窄、表字段的必填与类型检查<br>
 * 校验失败一律抛 Lua 错误，错误消息由参数用途说明（{@code what}）与字段名拼接
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

    /**
     * 校验参数为 Lua 表
     *
     * @param arg   参数原值
     * @param what  参数用途说明，用于错误消息
     * @param shape 期望形状说明，最终消息为{@code what + "必须是" + shape}
     * */
    public static @NonNull Table expectTable(@Nullable Object arg, @NonNull String what, @NonNull String shape) {
        if (arg instanceof Table table) return table;
        throw new LuaRuntimeException(what + "必须是" + shape);
    }

    /**
     * 读取表中必填数字字段
     *
     * @param table 字段所在表
     * @param field 字段名
     * @param what  字段所属参数的用途说明，用于错误消息
     * */
    public static double expectDoubleField(@NonNull Table table, @NonNull String field, @NonNull String what) {
        Object value = table.rawget(ByteString.of(field));
        if (value == null) throw new LuaRuntimeException(fieldLabel(what, field) + " 必填");
        return expectDouble(value, fieldLabel(what, field) + " ");
    }

    /**
     * 读取表中可选字符串字段
     *
     * @param table 字段所在表
     * @param field 字段名
     * @param what  字段所属参数的用途说明，用于错误消息
     *
     * @return 字段值；缺省返回{@code null}
     * */
    public static @Nullable String optStringField(
            @NonNull Table table, @NonNull String field, @NonNull String what) {
        Object value = table.rawget(ByteString.of(field));
        if (value == null) return null;
        return expectString(value, fieldLabel(what, field) + " ");
    }

    /**
     * 读取表中必填字符串字段
     *
     * @param table 字段所在表
     * @param field 字段名
     * @param what  字段所属参数的用途说明，用于错误消息
     * */
    public static @NonNull String expectStringField(
            @NonNull Table table, @NonNull String field, @NonNull String what) {
        String value = optStringField(table, field, what);
        if (value == null) throw new LuaRuntimeException(fieldLabel(what, field) + " 必填");
        return value;
    }

    /** 字段的完整错误消息前缀：{@code what + "的字段 \"field\""} */
    private static @NonNull String fieldLabel(@NonNull String what, @NonNull String field) {
        return what + "的字段 \"" + field + "\"";
    }
}
