package io.github.hhn756.voidairrace.addons.script.simplevalue;

import io.github.hhn756.voidairrace.infrastructure.util.lua.ApiArgs;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.Table;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 朝向值：Lua 朝向表 {@code {yaw=, pitch=}} ↔ Java 记录，单位为度<br>
 * {@code yaw} 为水平旋转，{@code pitch} 为俯仰
 * */
public record Rotation(float yaw, float pitch) {
    /**
     * 把 Lua 朝向描述符转换为朝向值
     *
     * @param arg  Lua 原值
     * @param what 参数用途说明，用于错误消息
     *
     * @return 朝向值
     * */
    public static @NonNull Rotation expect(@Nullable Object arg, @NonNull String what) {
        Table table = ApiArgs.expectTable(arg, what, "朝向表（{yaw=, pitch=}）");
        return new Rotation(
                (float) ApiArgs.expectDoubleField(table, "yaw", what),
                (float) ApiArgs.expectDoubleField(table, "pitch", what));
    }

    /**
     * 把朝向写成 Lua 朝向表
     *
     * @param stateContext 共享 Lua 状态上下文（建表用）
     * @param rot          朝向值
     *
     * @return 键为 {@code yaw} / {@code pitch} 的 Lua 表
     * */
    public static @NonNull Table toLua(
            @NonNull StateContext stateContext, @NonNull Rotation rot) {
        Table table = stateContext.newTable();
        table.rawset(ByteString.of("yaw"), (double) rot.yaw());
        table.rawset(ByteString.of("pitch"), (double) rot.pitch());
        return table;
    }
}
