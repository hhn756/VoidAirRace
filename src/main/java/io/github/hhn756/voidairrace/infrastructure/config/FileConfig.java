package io.github.hhn756.voidairrace.infrastructure.config;

import io.github.hhn756.voidairrace.exception.ConfigException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.File;

/**
 * 标记插件定义的配置对象行为
 */
public interface FileConfig {

    /**
     * @return 配置文件对应的定义对象
     * */
    @NonNull ConfigDefinition<?> getDefine();

    /**
     * 获取配置数据
     *
     * @param key 要读取的字段
     *
     * @return 如果字段存在且不为空则返回字段值，否则返回{@code null}
     * */
    default <T> T get(@NonNull ConfigKey<T> key) {
        return get(key, null);
    }

    /**
     * 获取配置数据
     *
     * @param key 要读取的字段
     * @param def 默认值，是字段为空或不存在时的返回值
     *
     * @return 如果字段存在且不为空则返回字段值，否则返回指定的默认值
     * */
    <T> T get(@NonNull ConfigKey<T> key, @Nullable T def);

    /**
     * 修改配置数据
     *
     * @param key 目标字段
     * @param value 新值
     * */
    <T> void set(@NonNull ConfigKey<T> key, @Nullable T value);

    /**
     * 将配置对象在内存中已进行过的所有修改保存到 源配置文件 中，失败时不会重试
     *
     * @throws ConfigException 操作过程中出现问题时抛出
     * */
    default void save() throws ConfigException {
        saveTo(new File(getDefine().filePath()), 0);
    }

    /**
     * 将配置对象在内存中已进行过的所有修改保存到 源配置文件 中，操作失败后最多重试指定次数此
     *
     * @param maxRetries 最大重试次数
     * */
    default void save(int maxRetries) throws ConfigException {
        saveTo(new File(getDefine().filePath()), maxRetries);
    }

    /**
     * 将配置对象在内存中已进行过的所有修改保存到 指定文件 中
     *
     * @param targetFile 要将配置数据保存到的文件
     * @param maxRetries 最大重试次数
     * */
    void saveTo(@NonNull File targetFile, int maxRetries) throws ConfigException;

    /**
     * 原子性地保存配置对象在内存中已进行过的所有修改（可选能力）
     *
     * @throws ConfigException 操作过程中出现问题时抛出
     * */
    default void saveAtomic() throws ConfigException {
        saveAtomic(new File(getDefine().filePath()));
    }

    /**
     * 原子性地保存配置对象在内存中已进行过的所有修改到指定文件中（可选能力）
     *
     * @param targetFile 要将配置数据保存到的文件
     *
     * @throws ConfigException 操作过程中出现问题时抛出
     * */
    default void saveAtomic(@NonNull File targetFile) throws ConfigException {
        throw new ConfigException("配置类 '" + getClass().getSimpleName() + "' 不支持原子保存", null);
    }

    /**
     * 重新从磁盘读取数据到配置实例中，这将完全用硬盘中的字段数据替换内存中的字段数据（可选能力）
     *
     * @throws ConfigException 操作过程中出现问题时抛出
     * */
    default void reload() throws ConfigException {
        throw new ConfigException("配置类 '" + getClass().getSimpleName() + "' 不支持重新加载", null);
    }
}
