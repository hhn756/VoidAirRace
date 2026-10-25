package io.github.hhn756.voidairrace.addons.script.simplevalue;

import io.github.hhn756.voidairrace.infrastructure.util.lua.ApiArgs;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.Table;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 浮点坐标值：Lua 坐标表 {@code {x=, y=, z=}} ↔ Java 记录<br>
 * 解析失败抛 Lua 错误，错误消息携带字段名与参数用途（{@code what} 参数）
 * */
public record Position(double x, double y, double z) {
    /**
     * 把 Lua 坐标描述符转换为浮点坐标
     *
     * @param arg  Lua 原值
     * @param what 参数用途说明，用于错误消息
     *
     * @return 坐标值
     * */
    public static @NonNull Position expect(@Nullable Object arg, @NonNull String what) {
        Table table = ApiArgs.expectTable(arg, what, "坐标表（{x=, y=, z=}）");
        return new Position(
                ApiArgs.expectDoubleField(table, "x", what),
                ApiArgs.expectDoubleField(table, "y", what),
                ApiArgs.expectDoubleField(table, "z", what));
    }

    /**
     * 把浮点坐标写成 Lua 坐标表
     *
     * @param stateContext 共享 Lua 状态上下文（建表用）
     * @param pos          坐标值
     *
     * @return 键为 {@code x} / {@code y} / {@code z} 的 Lua 表
     * */
    public static @NonNull Table toLua(@NonNull StateContext stateContext, @NonNull Position pos) {
        Table table = stateContext.newTable();
        table.rawset(ByteString.of("x"), pos.x());
        table.rawset(ByteString.of("y"), pos.y());
        table.rawset(ByteString.of("z"), pos.z());
        return table;
    }
}
