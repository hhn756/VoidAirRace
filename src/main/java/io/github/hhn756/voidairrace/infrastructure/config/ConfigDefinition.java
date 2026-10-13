package io.github.hhn756.voidairrace.infrastructure.config;

import io.github.hhn756.voidairrace.infrastructure.util.TypeReference;
import org.jspecify.annotations.NonNull;

/**
 * 代表一个已定义的配置文件，包含文件名、字段列表和配置实现类型<br>
 * 仅作数据载体，不包含实际逻辑
 * <p>
 * 构造器私有：实现类型当前固定为{@link YamlConfig}，经{@link #of}构造，
 * 实参中无需重复书写实现类型
 * */
public final class ConfigDefinition {
    /** 配置文件名/路径（不含扩展名） */
    private final @NonNull String filePath;

    /** 该配置文件中定义的所有配置键 */
    private final @NonNull ConfigKey<?>[] keys;

    /** 此配置文件所用的实现类型（用于运行时分发到对应加载器） */
    private final @NonNull TypeReference<YamlConfig> impl;

    private ConfigDefinition(
            @NonNull String filePath,
            @NonNull ConfigKey<?>[] keys,
            @NonNull TypeReference<YamlConfig> impl
    ) {
        this.filePath = filePath;
        this.keys = keys;
        this.impl = impl;
    }

    /**
     * 构造一个 Yaml 配置文件定义
     *
     * @param filePath 配置文件名/路径（不含扩展名）
     * @param keys     该配置文件中定义的所有配置键
     *
     * @return 配置文件定义对象
     * */
    public static @NonNull ConfigDefinition of(
            @NonNull String filePath,
            @NonNull ConfigKey<?>[] keys
    ) {
        return new ConfigDefinition(filePath, keys, new TypeReference<>() {});
    }

    /**
     * @return 配置文件名/路径（不含扩展名）
     * */
    public @NonNull String filePath() {
        return filePath;
    }

    /**
     * @return 该配置文件中定义的所有配置键
     * */
    public @NonNull ConfigKey<?>[] keys() {
        return keys;
    }

    /**
     * @return 此配置文件所用的实现类型
     * */
    public @NonNull Class<YamlConfig> implType() {
        return impl.getTypeClass();
    }
}
