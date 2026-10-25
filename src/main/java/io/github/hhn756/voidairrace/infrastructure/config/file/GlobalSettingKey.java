package io.github.hhn756.voidairrace.infrastructure.config.file;

import io.github.hhn756.voidairrace.infrastructure.config.ConfigKey;
import org.bukkit.Location;

import java.util.LinkedHashMap;
import java.util.Map;

public class GlobalSettingKey {
    /**
     * 服务端语言：取值为 {@code service.language.Language} 中的语言代码<br>
     * 值无法识别时回退到美式英语并记录警告
     * */
    public static final ConfigKey<String> LANGUAGE = new ConfigKey<>(
            "language", "zh_cn", "服务端语言：zh_cn（简体中文）或 en_us（美式英语）"
    ){};

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

    /**
     * 是否启用资源包分发：关闭时不监听任何端口、不下发资源包<br>
     * 常量名随子系统叫「分发」，键名仍为 {@code enable_resource_pack_http} 一族——键名是既有部署的运行时契约，改名会使其配置项全部作废
     * */
    public static final ConfigKey<Boolean> ENABLE_RESOURCE_PACK_DISTRIBUTION_HTTP = new ConfigKey<>(
            "enable_resource_pack_http", false, "是否启用资源包分发（内置只读 HTTP 托管 + 玩家进入时下发）"
    ){};

    /**
     * 资源包分发的对外地址：客户端真正去请求的完整 URL，须为明文 http<br>
     * 无默认主机可推导也无退化值——插件无法从监听地址反推「玩家从哪打进来」，因此留空即视为未配置、分发不启用
     * */
    public static final ConfigKey<String> RESOURCE_PACK_DISTRIBUTION_HTTP_EXTERNAL_ADDRESS = new ConfigKey<>(
            "resource_pack_http_external_address", "",
            "资源包分发的对外地址（客户端可访问的完整 URL，如 http://example.com:8123/pack.zip）；留空即不启用"
    ){};

    /** 资源包分发的监听端口：托管服务绑定全部网卡的这个端口；与对外地址中的端口可以不同（端口映射/反向代理） */
    public static final ConfigKey<Integer> RESOURCE_PACK_DISTRIBUTION_HTTP_LISTEN_PORT = new ConfigKey<>(
            "resource_pack_http_listen_port", 8123, "资源包分发的监听端口（绑定全部网卡）"
    ){};

    /**
     * 资源包分发的并发下载请求上限<br>
     * 可配而非写死：合适的水位取决于部署侧的带宽与同时在线的玩家数，插件无从推断；该值只用于防止这份内容被当作流量放大点
     * */
    public static final ConfigKey<Integer> RESOURCE_PACK_DISTRIBUTION_HTTP_MAX_CONCURRENT_REQUESTS = new ConfigKey<>(
            "resource_pack_http_max_concurrent_requests", 4,
            "资源包分发的并发下载请求上限（须为正整数）"
    ){};

    public static final ConfigKey<?>[] ALL_KEYS = {
            LANGUAGE,
            SPAWN_LOCATION,
            HIDE_ASCII_LOGO,
            ENABLE_RESOURCE_PACK_DISTRIBUTION_HTTP,
            RESOURCE_PACK_DISTRIBUTION_HTTP_EXTERNAL_ADDRESS,
            RESOURCE_PACK_DISTRIBUTION_HTTP_LISTEN_PORT,
            RESOURCE_PACK_DISTRIBUTION_HTTP_MAX_CONCURRENT_REQUESTS
    };
}
