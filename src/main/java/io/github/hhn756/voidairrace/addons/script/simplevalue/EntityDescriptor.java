package io.github.hhn756.voidairrace.addons.script.simplevalue;

import io.github.hhn756.voidairrace.infrastructure.util.lua.ApiArgs;
import net.kyori.adventure.text.Component;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.LuaRuntimeException;
import net.sandius.rembulan.Table;
import org.bukkit.Art;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Lua 实体描述符 → 生成参数的单向转换器<br>
 * 实体描述符在 Lua 侧是数据形状而非函数：脚本构造描述符表传给
 * {@code arena.spawn}，由接收点生成实体时使用
 * <p>
 * 描述符协议（snake_case）：
 * <ul>
 *   <li>{@code type}：必填，实体键，命名空间可省略；必须是可生成的非玩家实体</li>
 *   <li>{@code rot}：可选，朝向表 {@code {yaw=, pitch=}}，省略时由接收点取
 *       {@code {yaw = 0, pitch = 0}}</li>
 *   <li>{@code name}：可选，自定义名称，文本组件描述符</li>
 *   <li>{@code data}：可选，类型专属数据表，字段见 {@link Data}</li>
 * </ul>
 * 本类在接触世界前完成全部解析与能力校验：字段只允许写在具备对应能力的类型上，
 * 掉落物必须提供 {@code item}。能力按 {@link EntityType#getEntityClass()} 判定
 * */
public record EntityDescriptor(
        @NonNull EntityType type,
        @Nullable Rotation rot,
        @Nullable Component name,
        @NonNull Data data
) {
    /**
     * 把 Lua 实体描述符转换为 Java 对象
     *
     * @param arg  Lua 原值
     * @param what 参数用途说明，用于错误消息
     *
     * @return 实体描述符
     * */
    public static @NonNull EntityDescriptor expect(@Nullable Object arg, @NonNull String what) {
        Table table = ApiArgs.expectTable(
                arg, what, "实体描述符表（{type =, rot =, name =, data =}）");
        String typeKey = ApiArgs.expectStringField(table, "type", what);
        EntityType type = EntityTypeValue.parse(typeKey, what + "的字段 \"type\"");
        if (type == EntityType.PLAYER) {
            throw new LuaRuntimeException(what + "：玩家不能通过 arena.spawn 生成");
        }
        if (!type.isSpawnable()) {
            throw new LuaRuntimeException(
                    what + "的字段 \"type\" 不是可生成的实体类型：\"" + typeKey + "\"");
        }

        Object rawRot = table.rawget(ByteString.of("rot"));
        Rotation rot = rawRot == null
                ? null
                : Rotation.expect(rawRot, what + "的字段 \"rot\"");
        Object rawName = table.rawget(ByteString.of("name"));
        Component name = rawName == null
                ? null
                : TextComponents.fromLua(rawName, what + "的字段 \"name\"");
        Object rawData = table.rawget(ByteString.of("data"));
        Class<? extends Entity> clazz = type.getEntityClass();
        Data data;
        if (rawData == null) {
            if (clazz != null && Item.class.isAssignableFrom(clazz)) {
                throw new LuaRuntimeException(
                        what + "的字段 \"data\" 的字段 \"item\" 必填：掉落物需要物品内容");
            }
            data = Data.empty();
        } else {
            data = Data.expect(rawData, what + "的字段 \"data\"", clazz);
        }
        return new EntityDescriptor(type, rot, name, data);
    }

    /**
     * 类型专属数据：只保存脚本给出的字段，未给出的字段为{@code null}，
     * 由生成点按各字段的默认值处理<br>
     * {@code equipment} 与 {@code item} 是已解析的物品堆；{@code art} 与
     * {@code facing} 来自 {@code painting} 子表
     * */
    public record Data(
            @Nullable Boolean ai,
            @Nullable Boolean baby,
            @Nullable Boolean silent,
            @Nullable Boolean glowing,
            @Nullable Boolean invulnerable,
            @Nullable Boolean gravity,
            @Nullable Boolean persistent,
            @Nullable Boolean nameVisible,
            @Nullable Boolean invisible,
            @NonNull Map<EquipmentSlot, ItemStack> equipment,
            @Nullable ItemStack item,
            @Nullable Art art,
            @Nullable BlockFace facing,
            @Nullable Boolean armorStandArms,
            @Nullable Boolean armorStandSmall,
            @Nullable Boolean armorStandMarker,
            @Nullable Boolean armorStandBasePlate
    ) {
        /** 空数据：描述符没有 {@code data} 字段时使用 */
        static @NonNull Data empty() {
            return new Data(null, null, null, null, null, null, null, null, null,
                    Map.of(), null, null, null, null, null, null, null);
        }

        /**
         * 把 Lua 数据表转换为 Java 对象，并按实体类型校验能力
         *
         * @param arg   Lua 原值
         * @param what  参数用途说明，用于错误消息
         * @param clazz 实体类型对应的实体类；未知实体类型为{@code null}
         * */
        static @NonNull Data expect(
                @Nullable Object arg,
                @NonNull String what,
                @Nullable Class<? extends Entity> clazz
        ) {
            Table table = ApiArgs.expectTable(arg, what, "实体数据表");
            boolean living = clazz != null && LivingEntity.class.isAssignableFrom(clazz);
            boolean mob = clazz != null && Mob.class.isAssignableFrom(clazz);
            boolean ageable = clazz != null && Ageable.class.isAssignableFrom(clazz);

            Boolean ai = optBoolean(table, "ai", what);
            if (ai != null && !mob) throw capability(what, "ai", "生物");

            Boolean baby = optBoolean(table, "baby", what);
            if (baby != null && !ageable) throw capability(what, "baby", "可成长实体");

            Boolean silent = optBoolean(table, "silent", what);
            Boolean glowing = optBoolean(table, "glowing", what);
            Boolean invulnerable = optBoolean(table, "invulnerable", what);
            Boolean gravity = optBoolean(table, "gravity", what);
            Boolean persistent = optBoolean(table, "persistent", what);
            Boolean nameVisible = optBoolean(table, "name_visible", what);

            Boolean invisible = optBoolean(table, "invisible", what);
            if (invisible != null && !living) throw capability(what, "invisible", "活体");

            Map<EquipmentSlot, ItemStack> equipment = parseEquipment(table, what, living);
            ItemStack item = parseItem(table, what, clazz);

            Art art = null;
            BlockFace facing = null;
            Object rawPainting = table.rawget(ByteString.of("painting"));
            if (rawPainting != null) {
                if (clazz == null || !Painting.class.isAssignableFrom(clazz)) {
                    throw capability(what, "painting", "画");
                }
                Table painting = ApiArgs.expectTable(
                        rawPainting, what + "的字段 \"painting\"", "表（{art =, facing =}）");
                art = parseArt(painting, what + "的字段 \"painting\"");
                facing = parseFacing(painting, what + "的字段 \"painting\"");
            }

            Boolean arms = null;
            Boolean small = null;
            Boolean marker = null;
            Boolean basePlate = null;
            Object rawArmorStand = table.rawget(ByteString.of("armor_stand"));
            if (rawArmorStand != null) {
                if (clazz == null || !ArmorStand.class.isAssignableFrom(clazz)) {
                    throw capability(what, "armor_stand", "盔甲架");
                }
                Table armorStand = ApiArgs.expectTable(
                        rawArmorStand, what + "的字段 \"armor_stand\"", "表（{arms =, small =, marker =, base_plate =}）");
                String armorWhat = what + "的字段 \"armor_stand\"";
                arms = optBoolean(armorStand, "arms", armorWhat);
                small = optBoolean(armorStand, "small", armorWhat);
                marker = optBoolean(armorStand, "marker", armorWhat);
                basePlate = optBoolean(armorStand, "base_plate", armorWhat);
            }

            return new Data(ai, baby, silent, glowing, invulnerable, gravity, persistent,
                    nameVisible, invisible, equipment, item, art, facing,
                    arms, small, marker, basePlate);
        }

        /** 能力不符的错误：{@code what} 由调用点给出，字段名与所需能力拼入消息 */
        private static @NonNull LuaRuntimeException capability(
                @NonNull String what, @NonNull String field, @NonNull String ability) {
            return new LuaRuntimeException(
                    what + "的字段 \"" + field + "\" 只适用于" + ability);
        }

        /** 读取可选布尔字段；缺省返回{@code null}，非布尔即抛错 */
        private static @Nullable Boolean optBoolean(
                @NonNull Table table, @NonNull String field, @NonNull String what) {
            Object value = table.rawget(ByteString.of(field));
            if (value == null) return null;
            return ApiArgs.expectBoolean(value, what + "的字段 \"" + field + "\" ");
        }

        /** 装备表：只允许 {@code mainhand} / {@code offhand} / {@code head} / {@code chest} / {@code legs} / {@code feet} */
        private static @NonNull Map<EquipmentSlot, ItemStack> parseEquipment(
                @NonNull Table data, @NonNull String what, boolean living) {
            Object raw = data.rawget(ByteString.of("equipment"));
            if (raw == null) return Map.of();
            if (!living) throw capability(what, "equipment", "活体");

            String path = what + "的字段 \"equipment\"";
            Table table = ApiArgs.expectTable(raw, path,
                    "表（{mainhand =, offhand =, head =, chest =, legs =, feet =}）");
            Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
            putEquipment(equipment, table, "mainhand", EquipmentSlot.HAND, path);
            putEquipment(equipment, table, "offhand", EquipmentSlot.OFF_HAND, path);
            putEquipment(equipment, table, "head", EquipmentSlot.HEAD, path);
            putEquipment(equipment, table, "chest", EquipmentSlot.CHEST, path);
            putEquipment(equipment, table, "legs", EquipmentSlot.LEGS, path);
            putEquipment(equipment, table, "feet", EquipmentSlot.FEET, path);
            return Map.copyOf(equipment);
        }

        /** 读取装备表的一项，缺省跳过 */
        private static void putEquipment(
                @NonNull Map<EquipmentSlot, ItemStack> equipment,
                @NonNull Table table,
                @NonNull String field,
                @NonNull EquipmentSlot slot,
                @NonNull String what
        ) {
            Object raw = table.rawget(ByteString.of(field));
            if (raw != null) equipment.put(slot, ItemStackValue.expect(raw, what + "的字段 \"" + field + "\""));
        }

        /** 掉落物内容：掉落物必填，其余类型不允许出现 */
        private static @Nullable ItemStack parseItem(
                @NonNull Table data, @NonNull String what, @Nullable Class<? extends Entity> clazz) {
            boolean itemEntity = clazz != null && Item.class.isAssignableFrom(clazz);
            Object raw = data.rawget(ByteString.of("item"));
            if (raw == null) {
                if (itemEntity) {
                    throw new LuaRuntimeException(what + "的字段 \"item\" 必填：掉落物需要物品内容");
                }
                return null;
            }
            if (!itemEntity) throw capability(what, "item", "掉落物");
            return ItemStackValue.expect(raw, what + "的字段 \"item\"");
        }

        /** 画作键：命名空间可省略，未知画作抛错 */
        private static @Nullable Art parseArt(@NonNull Table table, @NonNull String what) {
            Object raw = table.rawget(ByteString.of("art"));
            if (raw == null) return null;
            String key = ApiArgs.expectString(raw, what + "的字段 \"art\"");
            NamespacedKey namespaced;
            try {
                namespaced = NamespacedKey.fromString(key);
            } catch (IllegalArgumentException e) {
                namespaced = null;
            }
            Art art = namespaced == null ? null : Registry.ART.get(namespaced);
            if (art == null) {
                throw new LuaRuntimeException(
                        what + "的字段 \"art\" 不是有效的画作键：\"" + key + "\"");
            }
            return art;
        }

        /** 附着方向：只接受 north / south / east / west */
        private static @Nullable BlockFace parseFacing(@NonNull Table table, @NonNull String what) {
            Object raw = table.rawget(ByteString.of("facing"));
            if (raw == null) return null;
            String name = ApiArgs.expectString(raw, what + "的字段 \"facing\"");
            switch (name.toLowerCase(Locale.ROOT)) {
                case "north": return BlockFace.NORTH;
                case "south": return BlockFace.SOUTH;
                case "east": return BlockFace.EAST;
                case "west": return BlockFace.WEST;
                default: throw new LuaRuntimeException(
                        what + "的字段 \"facing\" 必须是 north / south / east / west，实际：\"" + name + "\"");
            }
        }
    }
}
