package io.github.hhn756.voidairrace.infrastructure.config.file;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.infrastructure.config.ConfigDefinition;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PublicFile {
    /**
     * 存放所有临时文件的目录
     * */
    public static final Path TEMP_DIR = Paths.get(
            VoidAirRace.getInstance().getDataFolder().getAbsolutePath(), "temp/");

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
}