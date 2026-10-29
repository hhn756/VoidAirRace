package io.github.hhn756.voidairrace.addons.script.api.entity;

import io.github.hhn756.voidairrace.infrastructure.util.lua.ApiArgs;
import net.sandius.rembulan.LuaRuntimeException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * 实体入口的参数校验：UUID 字符串 → {@link UUID}<br>
 * 实体键的解析见 {@code EntityTypeValue}
 * */
public final class EntityArgs {
    /** 静态工具类，不可实例化 */
    private EntityArgs() {}

    /**
     * 把 Lua 参数按 UUID 解析
     *
     * @param arg  Lua 原值
     * @param what 参数用途说明，用于错误消息
     *
     * @return UUID
     * */
    public static @NonNull UUID expectUuid(@Nullable Object arg, @NonNull String what) {
        String text = ApiArgs.expectString(arg, what);
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new LuaRuntimeException(what + "不是有效的 UUID：\"" + text + "\"");
        }
    }
}
