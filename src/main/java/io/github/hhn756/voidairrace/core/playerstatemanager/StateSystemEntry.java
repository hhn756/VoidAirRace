package io.github.hhn756.voidairrace.core.playerstatemanager;

import io.github.hhn756.voidairrace.infrastructure.registry.CategoryId;
import io.github.hhn756.voidairrace.infrastructure.registry.DefaultSubtable;
import org.bukkit.NamespacedKey;
import org.jspecify.annotations.NonNull;

/**
 * 记录一个状态体系的信息；同时也是子表，记录体系中所有状态的单例对象
 * */
public class StateSystemEntry extends DefaultSubtable<PlayerState, NamespacedKey> {
    /**
     * 实例化状态体系项，本身作为{@link io.github.hhn756.voidairrace.constants.Categories#STATESYSTEM}类别的元素<br>
     * 同时记录体系中的所有状态
     * <p>
     * 此类构造器仅{@link StateRegistrar}可访问
     *
     * @param systemId 此项记录的状态体系的Id
     * @param defaultState 此项所记录的状态体系的默认状态
     */
    StateSystemEntry(
            @NonNull String systemId,
            @NonNull NamespacedKey defaultState
    ) {
        super(categoryId, PlayerState::getId);
        this.systemId = systemId;
        this.defaultState = defaultState;
    }

    /**
     * 给父类一个id以满足契约<br>
     * 注意：此子表实际上无法通过id访问，并且此子表所有实例的id强制一致
     * */
    private static final CategoryId<PlayerState, NamespacedKey, StateSystemEntry> categoryId = new CategoryId<>();

    /**
     * 此子类不支持此方法
     * <p>
     * 如果需要获取此实例所记录状态体系的Id请使用{@link StateSystemEntry#getSystemId()}
     *
     * @throws UnsupportedOperationException 调用时
     * */
    @Override
    public @NonNull CategoryId<PlayerState, NamespacedKey, ? extends DefaultSubtable<PlayerState, NamespacedKey>> getId()
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException("StateSystemEntry子类不支持getId方法");
    }

    /**
     * 此项记录的状态体系的Id
     * */
    private final @NonNull String systemId;

    /**
     * 此项所记录的状态体系的默认状态
     */
    private final @NonNull NamespacedKey defaultState;

    /**
     * @return 此项记录的状态体系的Id
     * */
    public @NonNull String getSystemId() {
        return systemId;
    }

    /**
     * @return 此项所记录状态体系中默认状态的Id
     * */
    public @NonNull NamespacedKey getDefaultState() {
        return defaultState;
    }
}
