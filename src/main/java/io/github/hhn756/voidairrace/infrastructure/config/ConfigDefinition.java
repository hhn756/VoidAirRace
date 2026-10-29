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

    /** 是否由插件管理：插件管理的配置在插件卸载时统一保存；外部定义写入后由写入方即时保存 */
    private final boolean managedByPlugin;

    private ConfigDefinition(
            @NonNull String filePath,
            @NonNull ConfigKey<?>[] keys,
            @NonNull TypeReference<YamlConfig> impl,
            boolean managedByPlugin
    ) {
        this.filePath = filePath;
        this.keys = keys;
        this.impl = impl;
        this.managedByPlugin = managedByPlugin;
    }

    /**
     * 构造一个由插件管理的 Yaml 配置文件定义
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
        return new ConfigDefinition(filePath, keys, new TypeReference<>() {}, true);
    }

    /**
     * 构造一个由外部写入方管理的 Yaml 配置文件定义<br>
     * 此类配置不参与插件卸载时的统一保存（写入方每次修改后自行保存）
     *
     * @param filePath 配置文件名/路径（不含扩展名）
     * @param keys     该配置文件中定义的所有配置键
     *
     * @return 配置文件定义对象
     * */
    public static @NonNull ConfigDefinition external(
            @NonNull String filePath,
            @NonNull ConfigKey<?>[] keys
    ) {
        return new ConfigDefinition(filePath, keys, new TypeReference<>() {}, false);
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

    /**
     * @return 是否由插件管理；{@code false} 表示写入方自行负责保存
     * */
    public boolean managedByPlugin() {
        return managedByPlugin;
    }
}
