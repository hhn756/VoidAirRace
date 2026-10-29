package io.github.hhn756.voidairrace.addons.script.simplevalue;

import io.github.hhn756.voidairrace.infrastructure.util.lua.ApiArgs;
import net.sandius.rembulan.LuaRuntimeException;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.EntityType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 实体键值：Lua 字符串 {@code "zombie"} / {@code "minecraft:zombie"} → Bukkit
 * {@link EntityType}<br>
 * 只接受具体实体键，不接受类别名、选择器或通配符；命名空间可省略
 * */
public final class EntityTypeValue {
    /** 静态工具类，不可实例化 */
    private EntityTypeValue() {}

    /**
     * 把 Lua 参数按实体键解析
     *
     * @param arg  Lua 原值
     * @param what 参数用途说明，用于错误消息
     *
     * @return 实体类型
     * */
    public static @NonNull EntityType expect(@Nullable Object arg, @NonNull String what) {
        return parse(ApiArgs.expectString(arg, what), what);
    }

    /**
     * 把实体键字符串解析为实体类型
     *
     * @param key  实体键，命名空间可省略
     * @param what 参数用途说明，用于错误消息
     *
     * @return 实体类型
     * */
    public static @NonNull EntityType parse(@NonNull String key, @NonNull String what) {
        NamespacedKey namespaced;
        try {
            namespaced = NamespacedKey.fromString(key);
        } catch (IllegalArgumentException e) {
            namespaced = null;
        }
        EntityType type = namespaced == null ? null : Registry.ENTITY_TYPE.get(namespaced);
        if (type == null) {
            throw new LuaRuntimeException(what + "不是有效的实体类型：\"" + key + "\"");
        }
        return type;
    }
}
