package io.github.hhn756.voidairrace.infrastructure.config;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;

/**
 * 代表一个已定义的配置键，包含键路径、预期类型、默认值与注释，不绑定到具体的配置文件路径<br>
 * 默认值与注释是<b>默认配置文件的唯一真相源</b>：配置文件缺失时由键定义物化生成，
 * 已有文件缺失键时由此补齐——不要手工维护默认配置文件
 *
 * @param <T> 字段类型，用于让读读取对象的方法实现静态类型安全
 */
public class ConfigKey<T> {
    /**
     * 键路径
     * */
    private final String path;
    /**
     * 缓存提取出来的完整泛型类型
     */
    private final Type type;
    /**
     * 默认值。可为{@code null}（该键不写入默认配置文件）<br>
     * 必须是 Bukkit YAML 可序列化的对象（基本类型、{@link String}、{@link java.util.List}、
     * {@link java.util.Map}、实现{@link org.bukkit.configuration.serialization.ConfigurationSerializable}的对象）
     */
    private final @Nullable Object defaultValue;
    /**
     * 写入默认配置文件的注释（单行，{@code # } 前缀由 Bukkit 自动添加）。可为{@code null}
     */
    private final @Nullable String comment;

    /**
     * 必须通过{@code new ConfigKey<字段类型>(键路径, 默认值, 注释){}}的形式实例化（匿名子类用于记录泛型类型）
     *
     * @param path         配置项在YAML文件中的路径（例如 {@code player_data}）
     * @param defaultValue 默认值，{@code null} 表示该键不写入默认配置文件
     * @param comment      写入默认配置文件的注释，可为{@code null}
     * @param <T> 键（字段）的值类型
     * */
    protected <T> ConfigKey(@NonNull String path, @Nullable Object defaultValue, @Nullable String comment) {
        this.path = path;
        this.defaultValue = defaultValue;
        this.comment = comment;

        // 1. 获取当前对象（匿名子类）的带有泛型信息的直接父类
        Type superClass = getClass().getGenericSuperclass();

        // 2. 确保父类是一个带有泛型参数的类型 (ParameterizedType)
        if (superClass instanceof ParameterizedType pType) {
            // 3. 提取父类泛型参数列表中的第一个参数
            this.type = pType.getActualTypeArguments()[0];
        } else {
            throw new IllegalArgumentException(
                    "读取 TypeToken 中记录的字段类型失败"
            );
        }
    }

    /**
     * 获取键对象对应的物理键路径
     * */
    public String path() {
        return path;
    }

    /**
     * 获取字段的预期类型
     * */
    public Type type() {
        return type;
    }

    /**
     * 获取默认值
     *
     * @return 默认值；{@code null} 表示该键不写入默认配置文件
     * */
    public @Nullable Object defaultValue() {
        return defaultValue;
    }

    /**
     * 获取写入默认配置文件的注释
     *
     * @return 注释文本；{@code null} 表示不写注释
     * */
    public @Nullable String comment() {
        return comment;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConfigKey<?> configKey = (ConfigKey<?>) o;
        return Objects.equals(path, configKey.path)
                && Objects.equals(type, configKey.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, type);
    }
}
