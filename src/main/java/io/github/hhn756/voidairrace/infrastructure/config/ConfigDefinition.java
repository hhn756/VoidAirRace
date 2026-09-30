package io.github.hhn756.voidairrace.infrastructure.config;

import io.github.hhn756.voidairrace.infrastructure.util.TypeReference;
import org.jspecify.annotations.NonNull;

/**
 * 代表一个已定义的配置文件，包含文件名、字段列表和配置实现类型<br>
 * 仅作数据载体，不包含实际逻辑
 *
 * @param filePath 配置文件名/路径（不含扩展名）
 * @param keys     该配置文件中定义的所有配置键
 * @param impl     此配置文件所用的 {@link FileConfig} 实现类型，
 *                 以 {@code new TypeReference<实现类>() {}} 的形式记录泛型实参
 *
 * @param <IMPL> 此配置文件所用的文件配置{@link FileConfig}实现类
 * */
public record ConfigDefinition<IMPL extends FileConfig>(
        @NonNull String filePath,
        @NonNull ConfigKey<?>[] keys,
        @NonNull TypeReference<IMPL> impl
) {}
