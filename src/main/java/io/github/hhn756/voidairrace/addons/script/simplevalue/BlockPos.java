package io.github.hhn756.voidairrace.addons.script.simplevalue;

import io.github.hhn756.voidairrace.infrastructure.util.lua.ApiArgs;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.Table;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 方块坐标（整数方块网格）：Lua 坐标表 {@code {x=, y=, z=}} ↔ Java 记录<br>
 * 浮点坐标逐分量向下取整落格，负数按 floor 落到所在方块（{@code -0.3} → {@code -1}），
 * 不做朝零截断
 * */
public record BlockPos(int x, int y, int z) {
    /**
     * 把 Lua 坐标描述符转换为方块坐标
     *
     * @param arg  Lua 原值
     * @param what 参数用途说明，用于错误消息
     *
     * @return 方块坐标
     * */
    public static @NonNull BlockPos expect(@Nullable Object arg, @NonNull String what) {
        Table table = ApiArgs.expectTable(arg, what, "坐标表（{x=, y=, z=}）");
        return new BlockPos(
                floorToInt(ApiArgs.expectDoubleField(table, "x", what), "x", what),
                floorToInt(ApiArgs.expectDoubleField(table, "y", what), "y", what),
                floorToInt(ApiArgs.expectDoubleField(table, "z", what), "z", what));
    }

    /**
     * 把方块坐标写成 Lua 坐标表
     *
     * @param stateContext 共享 Lua 状态上下文（建表用）
     * @param pos          方块坐标
     *
     * @return 键为 {@code x} / {@code y} / {@code z} 的 Lua 表，值为整数
     * */
    public static @NonNull Table toLua(@NonNull StateContext stateContext, @NonNull BlockPos pos) {
        Table table = stateContext.newTable();
        table.rawset(ByteString.of("x"), (long) pos.x());
        table.rawset(ByteString.of("y"), (long) pos.y());
        table.rawset(ByteString.of("z"), (long) pos.z());
        return table;
    }

    /** 向下取整并检查落在 int 范围内：Minecraft 世界坐标远小于 int 上限，越界视为脚本错误 */
    private static int floorToInt(double value, @NonNull String field, @NonNull String what) {
        double floored = Math.floor(value);
        if (floored < Integer.MIN_VALUE || floored > Integer.MAX_VALUE) {
            throw new net.sandius.rembulan.LuaRuntimeException(
                    what + "的字段 \"" + field + "\" 超出方块坐标范围：" + value);
        }
        return (int) floored;
    }
}
