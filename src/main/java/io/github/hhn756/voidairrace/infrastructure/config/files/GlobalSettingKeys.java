package io.github.hhn756.voidairrace.infrastructure.config.files;

import io.github.hhn756.voidairrace.infrastructure.config.ConfigKey;
import org.bukkit.Location;

import java.util.LinkedHashMap;
import java.util.Map;

public class GlobalSettingKeys {
    /**
     * 世界/大厅重生点位置<br>
     * 默认值采用 Location 的预序列化 Map 形式（含世界名字符串）：既不要求定义时存在世界实例，
     * 物化出的文件也与手工维护时期的默认块保持一致
     * */
    public static final ConfigKey<Location> SPAWN_LOCATION = new ConfigKey<>(
            "spawn_location", defaultSpawnLocation(), "世界/大厅重生点位置"
    ){};

    /**
     * @return 与 {@code new Location(Bukkit.getWorld("world"), 0.5, 64, 0.5)} 序列化结果等价的 Map
     * */
    private static Map<String, Object> defaultSpawnLocation() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("==", "org.bukkit.Location");
        map.put("world", "world");
        map.put("x", 0.5d);
        map.put("y", 64.0d);
        map.put("z", 0.5d);
        map.put("pitch", 0.0d);
        map.put("yaw", 0.0d);
        return map;
    }

    /** 是否隐藏启动时的字符画 Logo */
    public static final ConfigKey<Boolean> HIDE_ASCII_LOGO = new ConfigKey<>(
            "hide_ascii_logo", false, "是否隐藏启动时控制台的字符画 Logo"
    ){};

    public static final ConfigKey<?>[] ALL_KEYS = {
            SPAWN_LOCATION,
            HIDE_ASCII_LOGO
    };
}
