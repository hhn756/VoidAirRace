package io.github.hhn756.voidairrace.core.playerstatemanager;

import io.github.hhn756.voidairrace.constants.Categories;
import io.github.hhn756.voidairrace.constants.PlayerPDCKey;
import io.github.hhn756.voidairrace.constants.Plugin;
import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import io.github.hhn756.voidairrace.infrastructure.registry.Registry;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.NonNull;

import java.util.*;

/**
 * 玩家状态管理器，管理所有玩家的状态，包括状态转移、获取等操作<br>
 * 使用单例模式，用{@link PlayerStateManager#getInstance()}获取实例
 * */
public class PlayerStateManager implements Module {
    private static PlayerStateManager instance;

    public static PlayerStateManager getInstance() {
        if (instance == null) throw new NullPointerException("玩家状态管理器实例不存在");
        return instance;
    }

    // ------

    private PlayerStateManager() {}

    @Override
    public Collection<Class<? extends Module>> getRequiredModules() {
        return List.of(StateRegistrar.class, Registry.class);
    }

    /** 插件启用时执行 */
    private void onLoad() {
        instance = this;
    }

    /** 插件禁用时执行 */
    private void onUnload() {
        instance = null;
    }

    /**
     * 将指定玩家在指定状态体系切换到指定状态
     *
     * @param player 指定玩家
     * @param newState 新状态，自动识别状态所属体系
     * */
    public void toggle(Player player, NamespacedKey newState) {
        // 定位新状态所属的状态体系
        StateSystemEntry system = Registry.getInstance()
                .category(Categories.STATESYSTEM)
                .get(newState.getNamespace());
        if (system == null) throw new IllegalArgumentException(
                "状态体系 '" + newState.getNamespace() + "' 不存在");

        // 调用旧状态切出方法
        NamespacedKey oldState = getState(player, newState.getNamespace());
        if (oldState != null) {
            PlayerState oldStateInstance = system.get(oldState);
            // 旧状态可能来自失效的 PDC 数据，找不到实例时跳过切出
            if (oldStateInstance != null) oldStateInstance.onCutout(player);
        }

        // 调用新状态切入方法
        PlayerState newStateInstance = system.get(newState);
        if (newStateInstance == null) throw new IllegalArgumentException(
                "状态 '" + newState + "' 不存在");
        newStateInstance.onCutin(player);

        // 修改状态。注：将PDC更新放在出入方法后是为了防止出现异常时储存状态和实际状态不一致
        changeStateInPDC(player, newState);
    }

    /**
     * 获取玩家在指定状态体系的状态
     *
     * @param player 指定玩家
     * @param system 体系名
     *
     * @return 成功获取到状态时返回状态 ID，否则返回{@code null}
     * */
    public NamespacedKey getState(Player player, String system) {
        // 获取状态容器
        PersistentDataContainer statesContainer = checkStatesKey(player);

        // 获取状态
        String currentState = statesContainer.get(
                new NamespacedKey(Plugin.ns, system),
                PersistentDataType.STRING
        );
        return currentState == null ? null : new NamespacedKey(system, currentState);
    }

    /**
     * 更改指定玩家 PDC 中的状态值
     *
     * @param player 指定玩家
     * @param newState 新状态
     * */
    private void changeStateInPDC(Player player, NamespacedKey newState) {
        // 获取状态容器
        PersistentDataContainer statesPDC = checkStatesKey(player);

        // 设置新状态
        statesPDC.set(
                new NamespacedKey(Plugin.ns, newState.getNamespace()),
                PersistentDataType.STRING,
                newState.getKey()
        );
        player.getPersistentDataContainer().set(
                PlayerPDCKey.STATES.getValue(),
                PersistentDataType.TAG_CONTAINER,
                statesPDC
        );
    }

    /**
     * 如果指定玩家的 PDC 中不存在记录状态的键，那么创建它
     *
     * @param player 指定玩家
     *
     * @return 状态容器（玩家PDC中的子容器）
     * */
    private @NonNull PersistentDataContainer checkStatesKey(Player player) {
        PersistentDataContainer playerPDC = player.getPersistentDataContainer();
        NamespacedKey statesKey = PlayerPDCKey.STATES.getValue();
        // 尝试获取已有容器
        PersistentDataContainer statesContainer = playerPDC.get(statesKey, PersistentDataType.TAG_CONTAINER);
        if (statesContainer == null) {
            // 不存在则创建新容器并存入根容器
            statesContainer = playerPDC.getAdapterContext().newPersistentDataContainer();
            playerPDC.set(statesKey, PersistentDataType.TAG_CONTAINER, statesContainer);
        }
        return statesContainer;
    }

    /**
     * 获取指定玩家当前在所有体系中的状态
     *
     * @param player 指定玩家
     *
     * @return 键为体系名，值为对应状态
     */
    public Map<String, String> getAllStates(Player player) {
        HashMap<String, String> result = new HashMap<>();

        PersistentDataContainer states = checkStatesKey(player);
        Set<NamespacedKey> keys = states.getKeys();

        for (NamespacedKey key : keys) {
            result.put(key.getKey(), states.get(key, PersistentDataType.STRING));
        }
        return result;
    }

    /**
     * 检测指定玩家目前是否在指定状态中
     *
     * @param player 指定玩家
     * @param state 指定状态
     *
     * @return 如果指定玩家在指定状态中将返回{@code true}，否则返回{@code false}
     * */
    public boolean onState(Player player, NamespacedKey state) {
        NamespacedKey currentState = getState(player, state.getNamespace());
        return currentState != null && currentState.equals(state);
    }
}
