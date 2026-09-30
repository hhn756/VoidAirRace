package io.github.hhn756.voidairrace.infrastructure.config.files;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.infrastructure.config.ConfigDefinition;
import io.github.hhn756.voidairrace.infrastructure.config.YamlConfig;
import io.github.hhn756.voidairrace.infrastructure.util.TypeReference;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PublicFiles {
    /**
     * 存放所有临时文件的目录
     * */
    public static final Path TEMP_DIR = Paths.get(
            VoidAirRace.getInstance().getDataFolder().getAbsolutePath(), "temp/");

    /**
     * 游戏玩法相关设置
     * */
    public static final ConfigDefinition<YamlConfig> GAME_SETTINGS = new ConfigDefinition<>(
            "game_settings", GameSettingKeys.ALL_KEYS, new TypeReference<>() {});
    /**
     * 储存所有需要持久化的状态标记
     * */
    public static final ConfigDefinition<YamlConfig> FLAGS = new ConfigDefinition<>(
            "flags", FlagsKeys.ALL_KEYS, new TypeReference<>() {});
    /**
     * 全局设置
     * */
    public static final ConfigDefinition<YamlConfig> GLOBAL_SETTINGS = new ConfigDefinition<>(
            "global_settings", GlobalSettingKeys.ALL_KEYS, new TypeReference<>() {});
}