package io.github.hhn756.voidairrace.core.playerstatemanager;

import io.github.hhn756.voidairrace.constants.Categories;
import io.github.hhn756.voidairrace.constants.PlayerPDCKey;
import io.github.hhn756.voidairrace.event.PlayerInitEvent;
import io.github.hhn756.voidairrace.infrastructure.modules.ListenerProvider;
import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import io.github.hhn756.voidairrace.infrastructure.registry.Registry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;

public class PlayerInitializer implements Module, ListenerProvider, Listener {
    private static @Nullable PlayerInitializer instance;

    public static @NonNull PlayerInitializer getInstance() {
        if (instance == null) throw new NullPointerException("玩家初始化器实例不存在");
        return instance;
    }

    // ------

    private PlayerInitializer() {}

    @Override
    public @NonNull Collection<@NonNull Class<? extends Module>> getRequiredModules() {
        return List.of(StateRegistrar.class, PlayerStateManager.class);
    }

    @Override
    public @NonNull Collection<@NonNull Listener> getListeners() {
        return List.of(this);
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

    /** 插件禁用时执行 */
    private void onUnload() {
        instance = null;
    }

    @EventHandler(priority = EventPriority.LOWEST) // 确保进游戏后首先初始化
    public void onPlayerJoin(PlayerJoinEvent event) {
        initializePlayer(event.getPlayer());
    }

    /**
     * 初始化指定玩家，不会重复初始化（不会对已初始化过的指定玩家执行操作）
     *
     * @param player 指定玩家
     */
    public void initializePlayer(@NonNull Player player) {
        // 不重复初始化
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
    public void reInitPlayer(@NonNull Player player) {
        setInitState(player, false);
        initializePlayer(player);
    }

    /**
     * 检查玩家初始化状态
     *
     * @param player 指定玩家
     *
     * @return 如果指定玩家已初始化将返回{@code true}；如果指定玩家初始化状态为{@code null}或未初始化将返回{@code false}
     * */
    public boolean isInitialized(@NonNull Player player) {
        Boolean initState = player.getPersistentDataContainer().get(
                PlayerPDCKey.INITIALIZED.path(),
                PersistentDataType.BOOLEAN
        );
        return initState != null && initState;
    }

    private void setInitState(@NonNull Player player, @NonNull Boolean newValue) {
        player.getPersistentDataContainer().set(
                PlayerPDCKey.INITIALIZED.path(),
                PersistentDataType.BOOLEAN,
                newValue
        );
    }
}
