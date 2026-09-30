package io.github.hhn756.voidairrace.core.playerstatemanager;

import io.github.hhn756.voidairrace.constants.Categories;
import io.github.hhn756.voidairrace.constants.PlayerPDCKey;
import io.github.hhn756.voidairrace.event.PlayerInitEvent;
import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import io.github.hhn756.voidairrace.infrastructure.registry.Registry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collection;
import java.util.List;

public class PlayerInitializer implements Module {
    private static PlayerInitializer instance;

    public static PlayerInitializer getInstance() {
        if (instance == null) throw new NullPointerException("玩家初始化器实例不存在");
        return instance;
    }

    // ------

    private PlayerInitializer() {}

    @Override
    public Collection<Class<? extends Module>> getRequiredModules() {
        return List.of(StateRegistrar.class, PlayerStateManager.class);
    }

    /**
     * 插件启用时执行<br>
     * 初始化在线玩家必须在 StateRegistrar、PlayerStateManager 就绪后进行
     * */
    private void onLoad() {
        instance = this;

        // 初始化玩家
        for (Player player : Bukkit.getOnlinePlayers()) {
            initializePlayer(player);
        }
    }

    /** 插件停用时执行 */
    private void onUnload() {
        instance = null;
    }

    /**
     * 初始化指定玩家，如果它在此之前已经初始化过了那么不会执行任何操作
     *
     * @param player 指定玩家
     */
    public void initializePlayer(Player player) {
        // 检查玩家初始化状态
        if (isInitialized(player)) return;

        // 在所有状态体系中进入默认状态
        PlayerStateManager playerStateManager = PlayerStateManager.getInstance();
        for (StateSystemEntry system : Registry.getInstance().category(Categories.STATESYSTEM).list()) {
            playerStateManager.toggle(player, system.getDefaultState());
        }

        // 发布事件
        new PlayerInitEvent(player).callEvent();

        // 标记初始化
        setInitState(player, true);
    }

    /**
     * 重新初始化指定玩家
     * */
    public void reInitPlayer(Player player) {
        setInitState(player, false);
        initializePlayer(player);
    }

    public boolean isInitialized(Player player) {
        Boolean initState = player.getPersistentDataContainer().get(
                PlayerPDCKey.INITIALIZED.getValue(),
                PersistentDataType.BOOLEAN
        );
        return initState != null && initState;
    }

    private void setInitState(Player player, Boolean newValue) {
        player.getPersistentDataContainer().set(
                PlayerPDCKey.INITIALIZED.getValue(),
                PersistentDataType.BOOLEAN,
                newValue
        );
    }
}
