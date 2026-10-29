package io.github.hhn756.voidairrace.infrastructure.config.file;

import io.github.hhn756.voidairrace.infrastructure.config.ConfigKey;

/**
 * Lua API 配置文件（lua_config.yml）中定义的配置键
 * */
public final class LuaConfigKey {
    private LuaConfigKey() {}

    /** 单次 {@code arena.fill} 写入的方块数上限 */
    public static final ConfigKey<Integer> MAX_FILL_BLOCKS = new ConfigKey<>(
            "max_fill_blocks", 32768, "单次 arena.fill 写入的方块数上限"){};

    /**
     * 所有配置键的集合，便于批量注册
     */
    public static final ConfigKey<?>[] ALL_KEYS = {
            MAX_FILL_BLOCKS
    };
}
