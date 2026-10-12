package io.github.hhn756.voidairrace.constants;

import org.bukkit.NamespacedKey;

import java.util.Map;

/**
 * 玩家 PDC 中的字段路径
 * */
public enum PlayerPDCKey {
    /** 剩余钱数 */
    MONEY(new NamespacedKey("void_air_race", "money"), Integer.class),

    /** 在各状态体系的当前状态 */
    STATES(new NamespacedKey("void_air_race", "states"), Map.class),

    /** 初始化状态 */
    INITIALIZED(new NamespacedKey("void_air_race", "initialized"), Boolean.class),;

    private final NamespacedKey value;
    private final Class<?> type;

    PlayerPDCKey(NamespacedKey path, Class<?> type) {
        this.value = path;
        this.type = type;
    }

    public NamespacedKey path() {
        return value;
    }

    public Class<?> type() {
        return type;
    }
}
