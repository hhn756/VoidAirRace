package io.github.hhn756.voidairrace.core.map.maps.lobby;

import io.github.hhn756.voidairrace.constants.Categories;
import io.github.hhn756.voidairrace.core.map.MapEntry;
import io.github.hhn756.voidairrace.core.map.MapInitializer;
import io.github.hhn756.voidairrace.infrastructure.config.Config;
import io.github.hhn756.voidairrace.infrastructure.config.file.GameSettingKey;
import io.github.hhn756.voidairrace.infrastructure.config.file.PublicFile;
import io.github.hhn756.voidairrace.infrastructure.modules.ListenerProvider;
import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import io.github.hhn756.voidairrace.infrastructure.registry.Registry;
import org.bukkit.NamespacedKey;
import org.bukkit.event.Listener;
import org.jspecify.annotations.NonNull;

import java.util.Collection;
import java.util.List;

/**
 * 大堂初始化：根据当前选中的游戏地图，设置大堂中可用的队伍选择区域数量
 * */
public class LobbySetup implements Module, ListenerProvider {
    @Override
    public @NonNull Collection<@NonNull Class<? extends Module>> getRequiredModules() {
        // MAP 注册项类别由 MapInitializer 创建，必须晚于它加载
        return List.of(Config.class, Registry.class, MapInitializer.class);
    }

    @Override
    public @NonNull Collection<@NonNull Listener> getListeners() {
        return List.of(new EventListener());
    }

    /**
     * 插件启用时执行：按选中地图的最大队伍数设置激活的队伍选择区域数量
     * */
    private void onLoad() {
        NamespacedKey selectedMapId = NamespacedKey.fromString(
                Config.getInstance()
                        .getYmlConfig(PublicFile.GAME_SETTING)
                        .get(GameSettingKey.SELECTED_MAP_ID)
        );
        MapEntry mapEntry = Registry.getInstance().category(Categories.MAP).get(selectedMapId);
        if (mapEntry != null && mapEntry.maxTeams() != null) {
            State.activeTeamArea = mapEntry.maxTeams();  // 不会是null
        } else {
            State.activeTeamArea = 0;
        }
    }
}
