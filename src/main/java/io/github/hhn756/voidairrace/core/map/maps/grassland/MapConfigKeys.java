package io.github.hhn756.voidairrace.core.map.maps.grassland;

import io.github.hhn756.voidairrace.infrastructure.config.ConfigKey;
import org.bukkit.Location;

import java.util.List;

class MapConfigKeys {
    public static final ConfigKey<List<Location>> SUPPLY_CHESTS = new ConfigKey<>("supply_chests", null, null){};

    public static final ConfigKey<?>[] ALL_KEYS = {
            SUPPLY_CHESTS
    };
}
