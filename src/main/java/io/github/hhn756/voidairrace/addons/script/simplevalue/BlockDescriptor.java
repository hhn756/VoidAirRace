package io.github.hhn756.voidairrace.addons.script.simplevalue;

import io.github.hhn756.voidairrace.infrastructure.util.lua.ApiArgs;
import net.kyori.adventure.text.Component;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.LuaRuntimeException;
import net.sandius.rembulan.Table;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Lua 方块描述符 → Bukkit {@link BlockData} 与方块实体数据的单向转换器<br>
 * 方块描述符在 Lua 侧是数据形状而非函数：脚本构造描述符表传给接收点
 * （{@code arena.set}、{@code arena.fill}），由接收点在写入方块时使用
 * <p>
 * 描述符协议（snake_case）：
 * <ul>
 *   <li>{@code type}：必填，方块材质键，命名空间可省略（如 {@code "minecraft:oak_stairs"}、
 *       {@code "stone"}）</li>
 *   <li>{@code state}：可选，方块状态属性表 {@code {属性名 = 值}}，值为字符串、数字或布尔值，
 *       省略该字段等同于属性全部取默认值。与 {@code type} 拼接为原版方块状态字符串后交给
 *       {@link Bukkit#createBlockData(String)}（如 {@code "minecraft:oak_stairs[facing=north]"}）</li>
 *   <li>{@code data}：可选，方块实体数据表：
 *     <ul>
 *       <li>{@code name}：自定义名称，文本组件描述符</li>
 *       <li>{@code items}：容器物品栏数组，Lua 下标 1 对应槽位 0，每项
 *           {@code {item = 材质键, count = 数量}}，{@code count} 省略为 1</li>
 *       <li>{@code sign}：{@code {front = ..., back = ...}}，每侧为单个文本组件
 *           或最多 4 个文本组件组成的数组（按行）</li>
 *       <li>{@code command}：命令方块指令字符串</li>
 *       <li>{@code spawner}：{@code {entity = 实体键, delay = 延迟刻}}，{@code delay} 可省略</li>
 *     </ul>
 *   </li>
 * </ul>
 * 本类只校验描述符自身的形状与取值，不校验目标方块是否具备对应方块实体能力；
 * 能力检查在写入方块时进行
 * */
public record BlockDescriptor(
        @NonNull BlockData blockData,
        @Nullable BlockEntityData entityData
) {
    /**
     * 把 Lua 方块描述符转换为 Java 对象
     *
     * @param arg  Lua 原值
     * @param what 参数用途说明，用于错误消息
     *
     * @return 方块描述符
     * */
    public static @NonNull BlockDescriptor expect(@Nullable Object arg, @NonNull String what) {
        Table table = ApiArgs.expectTable(
                arg, what, "方块描述符表（{type =, state =, data =}）");
        String type = ApiArgs.expectStringField(table, "type", what);

        Object rawState = table.rawget(ByteString.of("state"));
        String properties = rawState == null
                ? ""
                : serializeState(rawState, what + "的字段 \"state\"");
        String serialized = properties.isEmpty() ? type : type + "[" + properties + "]";

        BlockData blockData;
        try {
            blockData = Bukkit.createBlockData(serialized);
        } catch (IllegalArgumentException e) {
            throw new LuaRuntimeException(
                    what + "的方块类型或状态无效：\"" + serialized + "\"");
        }

        Object rawData = table.rawget(ByteString.of("data"));
        BlockEntityData entityData = rawData == null
                ? null
                : BlockEntityData.expect(rawData, what + "的字段 \"data\"");
        return new BlockDescriptor(blockData, entityData);
    }

    /**
     * 把状态属性表拼成原版方块状态字符串的属性段（{@code 属性名=值,属性名=值}）<br>
     * 属性名必须是字符串，值接受字符串、数字与布尔值；空表返回空串
     *
     * @param arg  Lua 原值
     * @param what 参数用途说明，用于错误消息
     * */
    private static @NonNull String serializeState(@Nullable Object arg, @NonNull String what) {
        Table table = ApiArgs.expectTable(arg, what, "表（{属性名 = 值}）");
        StringBuilder properties = new StringBuilder();
        Object key = table.initialKey();
        while (key != null) {
            if (!(key instanceof ByteString property)) {
                throw new LuaRuntimeException(what + "的属性名必须是字符串");
            }
            String name = property.decode();
            if (name.isEmpty()) {
                throw new LuaRuntimeException(what + "的属性名不能为空字符串");
            }
            if (!properties.isEmpty()) properties.append(',');
            properties.append(name)
                    .append('=')
                    .append(stateValue(table.rawget(key), what + "的属性 \"" + name + "\""));
            key = table.successorKeyOf(key);
        }
        return properties.toString();
    }

    /**
     * 把单个属性值转为字符串<br>
     * 整数值的数字按整数输出（避免 Lua 数字统一为双精度而产生 {@code "0.0"}），非数字、字符串、
     * 布尔值一律抛错
     * */
    private static @NonNull String stateValue(@Nullable Object arg, @NonNull String what) {
        if (arg instanceof ByteString value) return value.decode();
        if (arg instanceof Boolean value) return value.toString();
        if (arg instanceof Long value) return Long.toString(value);
        if (arg instanceof Double value) {
            return value == Math.floor(value) && !value.isInfinite()
                    ? Long.toString(value.longValue())
                    : Double.toString(value);
        }
        throw new LuaRuntimeException(what + "必须是字符串、数字或布尔值");
    }

    /**
     * 方块实体数据：只描述内容，不绑定方块类型<br>
     * 各字段可同时出现，写入时逐项检查目标方块是否具备对应能力
     * */
    public record BlockEntityData(
            @Nullable Component name,
            @Nullable List<@Nullable ItemStack> items,
            @Nullable List<@NonNull Component> signFront,
            @Nullable List<@NonNull Component> signBack,
            @Nullable String command,
            @Nullable EntityType spawnerEntity,
            @Nullable Integer spawnerDelay
    ) {
        /** 告示牌每面行数 */
        private static final int SIGN_LINES = 4;

        /**
         * 把 Lua 实体数据表转换为 Java 对象
         *
         * @param arg  Lua 原值
         * @param what 参数用途说明，用于错误消息
         *
         * @return 实体数据
         * */
        public static @NonNull BlockEntityData expect(@Nullable Object arg, @NonNull String what) {
            Table table = ApiArgs.expectTable(arg, what, "方块实体数据表");

            Component name = null;
            Object rawName = table.rawget(ByteString.of("name"));
            if (rawName != null) {
                name = TextComponents.fromLua(rawName, what + "的字段 \"name\"");
            }

            List<ItemStack> items = null;
            Object rawItems = table.rawget(ByteString.of("items"));
            if (rawItems != null) {
                items = parseItems(rawItems, what + "的字段 \"items\"");
            }

            List<Component> signFront = null;
            List<Component> signBack = null;
            Object rawSign = table.rawget(ByteString.of("sign"));
            if (rawSign != null) {
                Table signTable = ApiArgs.expectTable(
                        rawSign, what + "的字段 \"sign\"", "表（{front =, back =}）");
                signFront = parseSignSide(signTable, "front", what);
                signBack = parseSignSide(signTable, "back", what);
                if (signFront == null && signBack == null) {
                    throw new LuaRuntimeException(
                            what + "的字段 \"sign\" 至少要提供 front 或 back");
                }
            }

            String command = ApiArgs.optStringField(table, "command", what);

            EntityType spawnerEntity = null;
            Integer spawnerDelay = null;
            Object rawSpawner = table.rawget(ByteString.of("spawner"));
            if (rawSpawner != null) {
                Table spawnerTable = ApiArgs.expectTable(
                        rawSpawner, what + "的字段 \"spawner\"", "表（{entity =, delay =}）");
                String entityKey = ApiArgs.expectStringField(spawnerTable, "entity", what + "的字段 \"spawner\"");
                spawnerEntity = parseEntityType(
                        entityKey, what + "的字段 \"spawner\" 的字段 \"entity\"");
                Object rawDelay = spawnerTable.rawget(ByteString.of("delay"));
                if (rawDelay != null) {
                    spawnerDelay = expectInt(
                            rawDelay, 0, Integer.MAX_VALUE,
                            what + "的字段 \"spawner\" 的字段 \"delay\"");
                }
            }

            return new BlockEntityData(
                    name, items, signFront, signBack, command, spawnerEntity, spawnerDelay);
        }

        /** 是否没有任何数据字段 */
        public boolean isEmpty() {
            return name == null
                    && items == null
                    && signFront == null
                    && signBack == null
                    && command == null
                    && spawnerEntity == null
                    && spawnerDelay == null;
        }

        /** 解析物品栏数组：下标 1 对应槽位 0，允许空项（Lua 侧 nil 会中断数组，空槽用空格位表达不了） */
        private static @NonNull List<@NonNull ItemStack> parseItems(
                @NonNull Object arg, @NonNull String what) {
            Table table = ApiArgs.expectTable(arg, what, "数组（每项 {item =, count =}）");
            List<ItemStack> items = new ArrayList<>();
            long index = 1;
            while (true) {
                Object rawItem = table.rawget(index);
                if (rawItem == null) break;
                items.add(parseItem(rawItem, what + " 第 " + index + " 项"));
                index++;
            }
            return List.copyOf(items);
        }

        /** 解析单个物品：形状见 {@link ItemStackValue} */
        private static @NonNull ItemStack parseItem(
                @NonNull Object arg, @NonNull String what) {
            return ItemStackValue.expect(arg, what);
        }

        /**
         * 解析告示牌单面内容：字符串或命名键表视为单个组件（占第 1 行），
         * 数值键表视为行数组（最多 4 行）
         * */
        private static @Nullable List<@NonNull Component> parseSignSide(
                @NonNull Table signTable, @NonNull String side, @NonNull String what) {
            Object rawSide = signTable.rawget(ByteString.of(side));
            if (rawSide == null) return null;
            String sideWhat = what + "的字段 \"sign\" 的字段 \"" + side + "\"";

            List<Component> lines;
            if (rawSide instanceof Table sideTable && sideTable.rawget(1) != null) {
                lines = TextComponents.listFromLua(rawSide, sideWhat);
            } else {
                lines = List.of(TextComponents.fromLua(rawSide, sideWhat));
            }
            if (lines.size() > SIGN_LINES) {
                throw new LuaRuntimeException(
                        sideWhat + "最多 " + SIGN_LINES + " 行，实际 " + lines.size() + " 行");
            }
            return lines;
        }

        /** 解析实体键：接受省略命名空间的写法，未知实体抛 Lua 错误 */
        private static @NonNull EntityType parseEntityType(
                @NonNull String entityKey, @NonNull String what) {
            NamespacedKey key;
            try {
                key = NamespacedKey.fromString(entityKey);
            } catch (IllegalArgumentException e) {
                key = null;
            }
            EntityType type = key == null ? null : Registry.ENTITY_TYPE.get(key);
            if (type == null) {
                throw new LuaRuntimeException(what + "不是有效的实体类型：\"" + entityKey + "\"");
            }
            return type;
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
}
