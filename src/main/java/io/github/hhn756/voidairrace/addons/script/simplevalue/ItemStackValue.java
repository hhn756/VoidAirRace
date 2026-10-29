package io.github.hhn756.voidairrace.addons.script.simplevalue;

import io.github.hhn756.voidairrace.infrastructure.util.lua.ApiArgs;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.LuaRuntimeException;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.Table;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 物品堆描述符：Lua 表 {@code {item = 材质键, count = 数量}} ↔ Bukkit {@link ItemStack}<br>
 * 同一形状用于方块容器物品、实体装备与掉落物内容；{@code count} 省略为 1，
 * 不得超过该物品的堆叠上限
 * */
public final class ItemStackValue {
    /** 静态工具类，不可实例化 */
    private ItemStackValue() {}

    /**
     * 把 Lua 物品堆描述符转换为物品堆
     *
     * @param arg  Lua 原值
     * @param what 参数用途说明，用于错误消息
     *
     * @return 物品堆
     * */
    public static @NonNull ItemStack expect(@Nullable Object arg, @NonNull String what) {
        Table table = ApiArgs.expectTable(arg, what, "表（{item =, count =}）");
        String itemKey = ApiArgs.expectStringField(table, "item", what);
        Material material = Material.matchMaterial(itemKey);
        if (material == null || !material.isItem()) {
            throw new LuaRuntimeException(
                    what + "的字段 \"item\" 不是有效的物品材质：\"" + itemKey + "\"");
        }
        Object rawCount = table.rawget(ByteString.of("count"));
        int count = rawCount == null
                ? 1
                : expectInt(rawCount, 1, material.getMaxStackSize(),
                        what + "的字段 \"count\"");
        return new ItemStack(material, count);
    }

    /**
     * 把物品堆写成 Lua 物品堆描述符表
     *
     * @param stateContext 共享 Lua 状态上下文（建表用）
     * @param stack        物品堆
     *
     * @return 键为 {@code item} / {@code count} 的 Lua 表
     * */
    public static @NonNull Table toLua(
            @NonNull StateContext stateContext, @NonNull ItemStack stack) {
        Table table = stateContext.newTable();
        table.rawset(ByteString.of("item"), ByteString.of(stack.getType().getKey().toString()));
        table.rawset(ByteString.of("count"), (long) stack.getAmount());
        return table;
    }

    /** 把 Lua 数字收窄为给定范围内的整数，非整数或越界抛 Lua 错误 */
    private static int expectInt(
            @NonNull Object arg, int min, int max, @NonNull String what) {
        double value = ApiArgs.expectDouble(arg, what);
        if (value != Math.floor(value) || value < min || value > max) {
            throw new LuaRuntimeException(
                    what + "必须是 " + min + " 到 " + max + " 之间的整数，实际：" + value);
        }
        return (int) value;
    }
}
