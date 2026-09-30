package io.github.hhn756.voidairrace.event;

import io.github.hhn756.voidairrace.infrastructure.config.ConfigDefinition;
import io.github.hhn756.voidairrace.infrastructure.config.ConfigKey;
import io.github.hhn756.voidairrace.infrastructure.util.TypeUtil;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 当配置文件中的某个字段值发生变更时触发的事件
 */
public class ConfigFieldChangeEvent extends Event {

    /** Bukkit 事件处理器列表 */
    private static final HandlerList HANDLERS = new HandlerList();

    /**
     * 获取 Bukkit 事件处理器列表（静态方法）
     *
     * @return 处理器列表
     */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public @NonNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /** 发生变更的配置文件的路径或名称，不含扩展名 */
    private final @NonNull ConfigDefinition<?> file;

    /**
     * 发生变更的路径<br>
     * <b>注：</b><br>
     * 事件里必须存变更的路径而不是键定义，因为：
     * <ul>
     *     <li>不一定所有路径都有对应的键定义</li>
     *     <li>如果存键定义，在修改配置值时调用方可以选择只传入路径而不传入键定义对象，这时必须通过提前创建{@code Map<路径, 配置键>}来获得键定义，这样会很复杂</li>
     * </ul>
     */
    private final @NonNull String path;

    /** 变更前的值 */
    private final @Nullable Object oldValue;

    /** 变更后的值 */
    private final @Nullable Object newValue;

    /**
     * 构造一个配置变更事件
     *
     * @param file     发生变更的配置文件的路径或名称，不含扩展名
     * @param path     发生变更的路径
     * @param oldValue 旧值
     * @param newValue 新值
     */
    public ConfigFieldChangeEvent(
            @NonNull ConfigDefinition<?> file,
            @NonNull  String       path,
            @Nullable Object       oldValue,
            @Nullable Object       newValue
    ) {
        this.file     = file;
        this.path     = path;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    /**
     * 获取发生变更的配置文件
     *
     * @return 代表配置文件的对象
     */
    public @NonNull ConfigDefinition<?> getFile() {
        return file;
    }

    /**
     * 获取发生变更的路径
     *
     * @return 键路径
     */
    public @NonNull String getPath() {
        return path;
    }

    /**
     * 获取字段变更前的值
     *
     * @param key 变更字段的定义，用于静态类型安全
     *
     * @return 旧值
     *
     * @throws ClassCastException 如果强制转换失败（配置被意外修改为错误类型）
     * @throws IllegalAccessError 如果传入的字段定义不是实际发生变更的字段
     */
    @SuppressWarnings("unchecked")
    public <T> @Nullable T getOldValue(ConfigKey<T> key) throws IllegalArgumentException {
        validatePath(key);
        Class<?> rawClass = TypeUtil.getRawClass(key.type());
        return (T) rawClass.cast(oldValue);
    }

    /**
     * 获取字段变更后的值
     *
     * @param key 变更字段的定义，用于静态类型安全
     *
     * @return 新值
     *
     * @throws ClassCastException 如果强制转换失败（配置被意外修改为错误类型）
     * @throws IllegalAccessError 如果传入的字段定义不是实际发生变更的字段
     */
    @SuppressWarnings("unchecked")
    public <T> @Nullable T getNewValue(ConfigKey<T> key) {
        validatePath(key);
        Class<?> rawClass = TypeUtil.getRawClass(key.type());
        return (T) rawClass.cast(newValue);
    }

    /**
     * 校验 {@link ConfigKey} 的路径是否与当前事件的路径一致
     */
    private void validatePath(ConfigKey<?> key) {
        if (!this.path.equals(key.path())) {
            throw new IllegalArgumentException(
                    "ConfigKey 的路径 \"" + key.path()
                    + "\" 与事件的路径 \"" + this.path + "\" 不匹配"
            );
        }
    }
}