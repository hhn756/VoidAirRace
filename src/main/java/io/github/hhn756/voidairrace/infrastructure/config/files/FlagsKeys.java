package io.github.hhn756.voidairrace.infrastructure.config.files;

import io.github.hhn756.voidairrace.infrastructure.config.ConfigKey;
import org.bukkit.configuration.ConfigurationSection;

public final class FlagsKeys {
    private FlagsKeys() {}

    /**
     * 所有地图的初始化状态
     * */
    public static final ConfigKey<ConfigurationSection> MAP_INIT = new ConfigKey<>("map_init", java.util.Map.of(), "所有地图的初始化状态"){};

    /**
     * 比赛是否因上次服务器关闭而被迫终止
     * */
    public static final ConfigKey<Boolean> MATCH_ABORTED = new ConfigKey<>("match_aborted", false, "比赛是否因上次服务器关闭而被迫终止"){};

    public static final ConfigKey<?>[] ALL_KEYS = {
            MAP_INIT, MATCH_ABORTED
    };
}