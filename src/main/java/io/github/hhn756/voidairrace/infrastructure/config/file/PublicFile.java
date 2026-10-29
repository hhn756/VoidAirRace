package io.github.hhn756.voidairrace.infrastructure.config.file;

import io.github.hhn756.voidairrace.infrastructure.config.ConfigDefinition;

public class PublicFile {
    /**
     * 存放所有临时文件的目录，相对插件数据目录<br>
     * 这里刻意存相对路径前缀：配置定义的文件路径一律按“相对配置目录”解析，
     * 塞入绝对路径会被配置层再拼接一次，得到无法创建的嵌套路径
     * */
    public static final String TEMP_DIR = "temp/";

    /**
     * 游戏玩法相关设置
     * */
    public static final ConfigDefinition GAME_SETTING = ConfigDefinition.of(
            "game_settings", GameSettingKey.ALL_KEYS);

    /**
     * 储存所有需要持久化的状态标记
     * */
    public static final ConfigDefinition FLAG = ConfigDefinition.of(
            "flags", FlagKey.ALL_KEYS);

    /**
     * 全局设置
     * */
    public static final ConfigDefinition GLOBAL_SETTING = ConfigDefinition.of(
            "global_settings", GlobalSettingKey.ALL_KEYS);

    /**
     * Lua API 相关设置
     * */
    public static final ConfigDefinition LUA = ConfigDefinition.of(
            "lua_config", LuaConfigKey.ALL_KEYS);
}