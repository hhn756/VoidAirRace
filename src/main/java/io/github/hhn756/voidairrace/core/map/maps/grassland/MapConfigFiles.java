package io.github.hhn756.voidairrace.core.map.maps.grassland;

import io.github.hhn756.voidairrace.core.map.GameMap;
import io.github.hhn756.voidairrace.infrastructure.config.ConfigDefinition;
import io.github.hhn756.voidairrace.infrastructure.config.YamlConfig;
import io.github.hhn756.voidairrace.infrastructure.util.TypeReference;

class MapConfigFiles {
    public static final ConfigDefinition<YamlConfig> DATA = new ConfigDefinition<>(
            GameMap.configPath(Const.MAP_ID, "data"),
            MapConfigKeys.ALL_KEYS,
            new TypeReference<YamlConfig>() {}
    );
}
