package io.github.hhn756.voidairrace.infrastructure.config;

import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.event.ConfigFieldChangeEvent;
import io.github.hhn756.voidairrace.exception.ConfigException;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * 代表一个 Yaml 配置文件<br>
 * 继承自 Bukkit 的 {@link YamlConfiguration}，支持静态键类型、字段修改事件、绑定到文件、原子性保存
 */
public class YamlConfig extends YamlConfiguration implements FileConfig {
    /**
     * 当前配置所属的配置文件枚举
     */
    private final ConfigDefinition<?> definition;

    /** 加载时的源文件，save()/saveAtomic() 无参形式的目标 */
    private final @NonNull File sourceFile;

    @Override
    public @NonNull ConfigDefinition<?> getDefine() {
        return definition;
    }

    /**
     * 构造一个 YAML 配置对象
     *
     * @param definition 源配置文件
     * @param sourceFile 加载时的源文件，作为无参保存的目标
     */
    public YamlConfig(@NonNull ConfigDefinition<?> definition, @NonNull File sourceFile) {
        this.definition = definition;
        this.sourceFile = sourceFile;
    }

    /**
     * 将配置对象在内存中已进行过的所有修改保存到加载时的源文件中<br>
     * 注意：定义对象中的{@code filePath}只是文件名，不能用于构造保存目标（会落到进程工作目录），
     * 因此保存一律以加载时的源文件为准
     * */
    @Override
    public void save() throws ConfigException {
        saveTo(sourceFile, 0);
    }

    /**
     * 原子性地保存配置对象在内存中已进行过的所有修改到加载时的源文件中
     *
     * @throws ConfigException 如果发生 I/O 错误或原子移动不被支持且回退失败
     * */
    @Override
    public void saveAtomic() throws ConfigException {
        saveAtomic(sourceFile);
    }

    @Override
    public void saveTo(@NonNull File targetFile, int maxRetries) throws ConfigException {
        try {
            // 调用 bukkit 原生保存逻辑，此处仅转义异常
            save(targetFile);
        } catch (IOException e) {
            throw new ConfigException(e.getMessage(), e, null);
        }
    }

    /**
     * 原子性地保存配置对象在内存中已进行过的所有修改到指定文件中
     *
     * @param targetFile 要将配置数据保存到的文件
     *
     * @throws ConfigException 如果发生 I/O 错误或原子移动不被支持且回退失败
     * */
    @Override
    public void saveAtomic(@NonNull File targetFile) throws ConfigException {
        String errMsg = "原子性的保存配置文件 失败: " + targetFile.getName();

        Path targetPath = targetFile.toPath().toAbsolutePath().normalize();
        Path parentDir = targetPath.getParent();
        Path tempFile;

        try {
            // 确保父目录存在
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            // 在同目录下创建一个临时文件
            if (parentDir != null) {
                tempFile = Files.createTempFile(parentDir, targetFile.getName(), ".tmp");
            } else {
                tempFile = Files.createTempFile(targetFile.getName(), ".tmp");
            }
        } catch (IOException e) {
            throw new ConfigException(
                    errMsg,
                    e,
                    Component.translatable(
                            TranslateKeys.Config.SAVE_ATOMIC_CANT_SAVE
                    )
            );
        }

        try {
            // 1. 将配置内容保存到临时文件
            super.save(tempFile.toFile());

            // 2. 原子性地移动临时文件到目标路径，替换已存在的文件
            try {
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (UnsupportedOperationException | IOException e) {
                // 某些文件系统（如部分 Windows 环境或跨分区）可能不支持 ATOMIC_MOVE，回退到普通替换
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            // 如果保存或移动失败，尝试清理临时文件
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException deleteEx) {
                e.addSuppressed(deleteEx);
            }
            throw new ConfigException(
                    errMsg,
                    e,
                    Component.translatable(TranslateKeys.Config.SAVE_ATOMIC_CANT_SAVE)
            );
        }
    }

    /**
     * 设置指定路径的值，如果新旧值不相等将触发 {@link ConfigFieldChangeEvent}
     *
     * @param path     配置项路径
     * @param newValue 新值
     */
    @Override
    public void set(@NonNull String path, Object newValue) {
        Object oldValue = get(path);
        super.set(path, newValue);

        if (!Objects.equals(oldValue, newValue)) {
            new ConfigFieldChangeEvent(definition, path, oldValue, newValue).callEvent();
        }
    }

    /**
     * 获取键所指定路径的值
     *
     * @param key 配置键
     *
     * @return 结果值
     *
     * @throws ClassCastException 如果键预期的值类型和实际值类型不兼容（无法强制转换）
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(@NonNull ConfigKey<T> key) throws ClassCastException {
        return (T) get(key.path());
    }

    /**
     * 获取键所指定路径的值
     *
     * @param key 配置键
     * @param def 默认值
     *
     * @return 如果目标字段不存在或值为{@code null}返回默认值，否则返回字段值
     *
     * @throws ClassCastException 如果键预期的值类型和实际值类型不兼容（无法强制转换）
     */
    @Override
    @Contract("_, !null -> !null")
    @SuppressWarnings("unchecked")
    public <T> T get(@NonNull ConfigKey<T> key, @Nullable T def) throws ClassCastException {
        // 父类已经完全实现了“不存在或为 null 时返回 def”的逻辑
        return (T) get(key.path(), def);
    }

    /**
     * 设置指定配置键的值
     *
     * @param key   配置键
     * @param value 新值
     */
    @Override
    public <T> void set(@NonNull ConfigKey<T> key, @Nullable T value) {
        set(key.path(), value);
    }

    /**
     * 检查配置键是否存在
     *
     * @param key 配置键
     *
     * @return 如果存在则返回 true
     */
    public boolean contains(@NonNull ConfigKey<?> key) {
        return contains(key.path());
    }

    /**
     * 检查配置键是否存在，可选择深度检查（考虑子路径）
     *
     * @param key  配置键
     * @param deep 是否深度检查
     *
     * @return 如果存在则返回 true
     */
    public boolean contains(@NonNull ConfigKey<?> key, boolean deep) {
        return contains(key.path(), deep);
    }

    @Override
    public @Nullable YamlSection getConfigurationSection(@NonNull String path) {
        ConfigurationSection section = super.getConfigurationSection(path);
        return section == null ? null : new YamlSection(section, definition);
    }

    public @Nullable YamlSection getSection(@NonNull String path) {
        return getConfigurationSection(path);
    }
}
