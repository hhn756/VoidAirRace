package io.github.hhn756.voidairrace.core.map.maps.grassland;

import io.github.hhn756.voidairrace.core.map.GameMap;
import io.github.hhn756.voidairrace.infrastructure.config.ConfigDefinition;

class MapConfigFiles {
    public static final ConfigDefinition DATA = ConfigDefinition.of(
            GameMap.configPath(Const.MAP_ID, "data"),
            MapConfigKeys.ALL_KEYS
    );
}
