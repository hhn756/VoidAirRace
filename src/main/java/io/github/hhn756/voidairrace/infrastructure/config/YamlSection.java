package io.github.hhn756.voidairrace.infrastructure.config;

import io.github.hhn756.voidairrace.event.ConfigFieldChangeEvent;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link ConfigurationSection} 的包装类，提供与 {@link YamlConfig} 相同的自定义功能（事件触发、ConfigKey 支持等）
 */
public class YamlSection implements ConfigurationSection {
    private final ConfigurationSection delegate;
    private final ConfigDefinition<?> source;

    public YamlSection(@NonNull ConfigurationSection delegate, @NonNull ConfigDefinition<?> source) {
        this.delegate = delegate;
        this.source = source;
    }

    @Override
    public void set(@NonNull String path, @Nullable Object value) {
        Object oldValue = delegate.get(path);
        delegate.set(path, value);
        if (!Objects.equals(oldValue, value)) {
            // 计算完整路径用于事件
            String fullPath = delegate.getCurrentPath().isEmpty()
                    ? path
                    : delegate.getCurrentPath() + "." + path;
            new ConfigFieldChangeEvent(source, fullPath, oldValue, value).callEvent();
        }
    }

    @Override
    public @Nullable YamlSection getConfigurationSection(@NonNull String path) {
        ConfigurationSection section = delegate.getConfigurationSection(path);
        return section == null ? null : new YamlSection(section, source);
    }

    @Override
    public @NonNull YamlSection createSection(@NonNull String path) {
        return new YamlSection(delegate.createSection(path), source);
    }

    @Override
    public @NonNull YamlSection createSection(@NonNull String path, @NonNull Map<?, ?> map) {
        return new YamlSection(delegate.createSection(path, map), source);
    }

    /**
     * 获取一个值
     *
     * @param key 键
     *
     * @return 一个由键对象指定路径和类型的值
     * */
    @SuppressWarnings("unchecked")
    public <T> T get(@NonNull ConfigKey<T> key) {
        return (T) delegate.get(key.path());
    }

    /**
     * 获取一个值，不存在时返回默认值
     *
     * @param key 键
     *
     * @return 一个由键对象指定路径和类型的值
     * */
    @SuppressWarnings("unchecked")
    public <T> T get(@NonNull ConfigKey<T> key, @NonNull T def) {
        return (T) delegate.get(key.path(), def);
    }

    /**
     * 设置指定键的值
     *
     * @param key 指定键
     * */
    public <T> void set(@NonNull ConfigKey<T> key, @Nullable T value) {
        set(key.path(), value); // 调用重写的 set 方法以触发事件
    }

    public boolean contains(@NonNull ConfigKey<?> key) {
        return delegate.contains(key.path());
    }

    // ==================== 委托给原生 ConfigurationSection 的方法 ====================

    @Override public @NonNull Set<String> getKeys(boolean deep) { return delegate.getKeys(deep); }
    @Override public @NonNull Map<String, Object> getValues(boolean deep) { return delegate.getValues(deep); }
    @Override public boolean contains(@NonNull String path) { return delegate.contains(path); }
    @Override public boolean contains(@NonNull String path, boolean ignoreDefault) { return delegate.contains(path, ignoreDefault); }
    @Override public boolean isSet(@NonNull String path) { return delegate.isSet(path); }
    @Override public @Nullable String getCurrentPath() { return delegate.getCurrentPath(); }
    @Override public @NonNull String getName() { return delegate.getName(); }
    @Override public @Nullable Configuration getRoot() { return delegate.getRoot(); }
    @Override public @Nullable ConfigurationSection getParent() { return delegate.getParent(); }
    @Override public @Nullable Object get(@NonNull String path) { return delegate.get(path); }
    @Override public @Nullable Object get(@NonNull String path, @Nullable Object def) { return delegate.get(path, def); }
    @Override public @Nullable String getString(@NonNull String path) {return delegate.getString(path);}
    @Contract("_, !null -> !null") @Override public @Nullable String getString(@NonNull String path, @Nullable String def) {return delegate.getString(path, def);}
    @Override public boolean isString(@NonNull String path) {return delegate.isString(path);}
    @Override public int getInt(@NonNull String path) {return delegate.getInt(path);}
    @Override public int getInt(@NonNull String path, int def) {return delegate.getInt(path, def);}
    @Override public boolean isInt(@NonNull String path) {return delegate.isInt(path);}
    @Override public boolean getBoolean(@NonNull String path) {return delegate.getBoolean(path);}
    @Override public boolean getBoolean(@NonNull String path, boolean def) {return delegate.getBoolean(path, def);}
    @Override public boolean isBoolean(@NonNull String path) {return delegate.isBoolean(path);}
    @Override public double getDouble(@NonNull String path) {return delegate.getDouble(path);}
    @Override public double getDouble(@NonNull String path, double def) {return delegate.getDouble(path, def);}
    @Override public boolean isDouble(@NonNull String path) {return delegate.isDouble(path);}
    @Override public long getLong(@NonNull String path) {return delegate.getLong(path);}
    @Override public long getLong(@NonNull String path, long def) {return delegate.getLong(path, def);}
    @Override public boolean isLong(@NonNull String path) {return delegate.isLong(path);}
    @Override public @Nullable List<?> getList(@NonNull String path) {return delegate.getList(path);}
    @Contract("_, !null -> !null") @Override public @Nullable List<?> getList(@NonNull String path, @Nullable List<?> def) {return delegate.getList(path, def);}
    @Override public boolean isList(@NonNull String path) {return delegate.isList(path);}
    @Override public @NonNull List<String> getStringList(@NonNull String path) {return delegate.getStringList(path);}
    @Override public @NonNull List<Integer> getIntegerList(@NonNull String path) {return delegate.getIntegerList(path);}
    @Override public @NonNull List<Boolean> getBooleanList(@NonNull String path) {return delegate.getBooleanList(path);}
    @Override public @NonNull List<Double> getDoubleList(@NonNull String path) {return delegate.getDoubleList(path);}
    @Override public @NonNull List<Float> getFloatList(@NonNull String path) {return delegate.getFloatList(path);}
    @Override public @NonNull List<Long> getLongList(@NonNull String path) {return delegate.getLongList(path);}
    @Override public @NonNull List<Byte> getByteList(@NonNull String path) {return delegate.getByteList(path);}
    @Override public @NonNull List<Character> getCharacterList(@NonNull String path) {return delegate.getCharacterList(path);}
    @Override public @NonNull List<Short> getShortList(@NonNull String path) {return delegate.getShortList(path);}
    @Override public @NonNull List<Map<?, ?>> getMapList(@NonNull String path) {return delegate.getMapList(path);}
    @Override public @Nullable <T> T getObject(@NonNull String path, @NonNull Class<T> clazz) {return delegate.getObject(path, clazz);}
    @Contract("_, _, !null -> !null") @Override public @Nullable <T> T getObject(@NonNull String path, @NonNull Class<T> clazz, @org.jspecify.annotations.Nullable T def) {return delegate.getObject(path, clazz, def);}
    @Override public @Nullable <T extends ConfigurationSerializable> T getSerializable(@NonNull String path, @NonNull Class<T> clazz) {return delegate.getSerializable(path, clazz);}
    @Contract("_, _, !null -> !null") @Override public @Nullable <T extends ConfigurationSerializable> T getSerializable(@NonNull String path, @NonNull Class<T> clazz, @org.jspecify.annotations.Nullable T def) {return delegate.getSerializable(path, clazz, def);}
    @Override public @Nullable Vector getVector(@NonNull String path) {return delegate.getVector(path);}
    @Contract("_, !null -> !null") @Override public @Nullable Vector getVector(@NonNull String path, @Nullable Vector def) {return delegate.getVector(path, def);}
    @Override public boolean isVector(@NonNull String path) {return delegate.isVector(path);}
    @Override public @Nullable OfflinePlayer getOfflinePlayer(@NonNull String path) {return delegate.getOfflinePlayer(path);}
    @Contract("_, !null -> !null") @Override public @Nullable OfflinePlayer getOfflinePlayer(@NonNull String path, @Nullable OfflinePlayer def) {return delegate.getOfflinePlayer(path, def);}
    @Override public boolean isOfflinePlayer(@NonNull String path) {return delegate.isOfflinePlayer(path);}
    @Override public @Nullable ItemStack getItemStack(@NonNull String path) {return delegate.getItemStack(path);}
    @Contract("_, !null -> !null") @Override public @Nullable ItemStack getItemStack(@NonNull String path, @Nullable ItemStack def) {return delegate.getItemStack(path, def);}
    @Override public boolean isItemStack(@NonNull String path) {return delegate.isItemStack(path);}
    @Override public @Nullable Color getColor(@NonNull String path) {return delegate.getColor(path);}
    @Contract("_, !null -> !null") @Override public @Nullable Color getColor(@NonNull String path, @Nullable Color def) {return delegate.getColor(path, def);}
    @Override public boolean isColor(@NonNull String path) {return delegate.isColor(path);}
    @Override public @Nullable Location getLocation(@NonNull String path) {return delegate.getLocation(path);}
    @Contract("_, !null -> !null") @Override public @Nullable Location getLocation(@NonNull String path, @Nullable Location def) {return delegate.getLocation(path, def);}
    @Override public boolean isLocation(@NonNull String path) {return delegate.isLocation(path);}
    @Override public boolean isConfigurationSection(@NonNull String path) {return delegate.isConfigurationSection(path);}
    @Override public @Nullable ConfigurationSection getDefaultSection() {return delegate.getDefaultSection();}
    @Override public void addDefault(@NonNull String path, @Nullable Object value) {delegate.addDefault(path, value);}
    @Override public @NonNull List<String> getComments(@NonNull String path) {return delegate.getComments(path);}
    @Override public @NonNull List<String> getInlineComments(@NonNull String path) {return delegate.getInlineComments(path);}
    @Override public void setComments(@NonNull String path, @Nullable List<String> comments) {delegate.setComments(path, comments);}
    @Override public void setInlineComments(@NonNull String path, @Nullable List<String> comments) {delegate.setInlineComments(path, comments);}
}