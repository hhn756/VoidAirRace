package io.github.hhn756.voidairrace.core.playerstatemanager;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

/**
 * 一个玩家状态
 * */
public interface PlayerState {
    /**
     * 获取此状态的标识符<br>
     * 命名空间（{@link NamespacedKey#getNamespace()}）表示此状态所属体系，键（{@link NamespacedKey#getKey()}）表示此状态的id
     * */
    NamespacedKey getId();

    /**
     * 切入到此状态时执行
     *
     * @param player 切入到此状态的玩家
     * */
    default void onCutin(Player player) {}

    /**
     * 从此状态切出时执行
     *
     * @param player 从此状态切出的玩家
     * */
    default void onCutout(Player player) {}
}
