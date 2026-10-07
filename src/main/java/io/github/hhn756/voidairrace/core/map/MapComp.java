package io.github.hhn756.voidairrace.core.map;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.constants.Categories;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.map.maps.grassland.GrassLand;
import io.github.hhn756.voidairrace.core.match.ComponentPriority;
import io.github.hhn756.voidairrace.core.match.DataKey;
import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.core.match.componentbase.*;
import io.github.hhn756.voidairrace.infrastructure.config.Config;
import io.github.hhn756.voidairrace.infrastructure.config.files.GameSettingKeys;
import io.github.hhn756.voidairrace.infrastructure.config.files.PublicFiles;
import io.github.hhn756.voidairrace.infrastructure.registry.Registry;
import io.github.hhn756.voidairrace.result.OperationResult;
import io.github.hhn756.voidairrace.result.ValueResult;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.Range;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class MapComp extends MatchComp
        implements ConfigurableComp<MapComp.MapECFG, MapComp.MapConfig>,
        StartableComp<CustomData, MapComp.MapSC>,
        EndableComp<CustomData, CustomData> {

    // -------------------- ConfigurableComp --------------------

    public static final DataKey<MapConfig> CONFIG_KEY = DataKey.of(MapComp.class, MapConfig.class);

    @Override
    public DataKey<MapConfig> getConfigKey() {
        return CONFIG_KEY;
    }

    @Override
    public @NonNull ValueResult<MapConfig> createCustomConfig(@Nullable MapECFG expected) {
        if (expected == null) {
            // 回退到默认配置：直接转发其结果，失败原因随之上传
            return createDefaultConfig();
        }

        MapEntry mapEntry = Registry.getInstance().category(Categories.MAP).get(expected.expectedMapId());

        // 如果地图不存在（地图 id 属于服务端参数，只进技术性消息）
        if (mapEntry == null) return ValueResult.failure(
                TranslateKeys.Map.CREATE_DEFAULT_CONFIG_MAP_NOTFOUND,
                "地图未注册：" + expected.expectedMapId()
        );
        // 如果地图不可玩
        if (!mapEntry.isPlayable()) return ValueResult.failure(
                TranslateKeys.Map.CREATE_CUSTOM_CONFIG_MAP_NOT_PLAYABLE,
                "地图不可游玩：" + expected.expectedMapId()
        );

        return ValueResult.success(new MapConfig((PlayableGameMap) mapEntry.newInstance()));
    }

    @Override
    public @NonNull ValueResult<MapConfig> createDefaultConfig() {
        NamespacedKey selectedMapId = NamespacedKey.fromString(
                Config.getInstance()
                        .getYmlConfig(PublicFiles.GAME_SETTINGS)
                        .get(GameSettingKeys.SELECTED_MAP_ID, GrassLand.getID().toString())
        );
        MapEntry mapEntry = Registry.getInstance().category(Categories.MAP).get(selectedMapId);

        // 如果地图不存在（地图 id 属于服务端参数，只进技术性消息）
        if (mapEntry == null) return ValueResult.failure(
                TranslateKeys.Map.CREATE_DEFAULT_CONFIG_MAP_NOTFOUND,
                "地图未注册：" + selectedMapId
        );
        // 如果地图不可玩
        if (!mapEntry.isPlayable()) return ValueResult.failure(
                TranslateKeys.Map.CREATE_DEFAULT_CONFIG_MAP_NOT_PLAYABLE,
                "地图不可游玩：" + selectedMapId
        );

        return ValueResult.success(new MapConfig((PlayableGameMap) mapEntry.newInstance()));
    }

    @Override
    public @Range(from = 0, to = Integer.MAX_VALUE) int getConfigPriority() {
        return ComponentPriority.LOW.getValue();
    }

    // -------------------- StartableComp --------------------

    private static final DataKey<MapSC> START_KEY = DataKey.of(MapComp.class, MapSC.class);

    @Override
    public @NonNull DataKey<MapSC> getSCK() {
        return START_KEY;
    }

    @Override
    public @Range(from = 0, to = Integer.MAX_VALUE) int getInstallPriority() {
        return ComponentPriority.LOW.getValue();
    }

    @Override
    public @NonNull ValueResult<MapSC> install(
            @NonNull Match match,
            @Nullable CustomData startArg) {

        PlayableGameMap gameMap = match.configOf(MapComp.CONFIG_KEY).map();

        // 调用地图的开始方法；地图开始失败时，把地图给出的原因包装到“地图组件启动失败”键对下返回
        OperationResult startResult = gameMap.start(match);
        if (!startResult.isSuccess()) {
            return ValueResult.<MapSC>fromOperation(startResult)
                    .causedBy(
                            TranslateKeys.Map.MAP_COMPONENT_SELECTED_START_FAILED,
                            TranslateKeys.Map.MAP_COMPONENT_SELECTED_START_FAILED_UNKNOWN_CAUSE
                    );
        }

        // 注册是 bukkit 事件监听器的地图
        if (gameMap instanceof Listener listener) {
            Bukkit.getPluginManager().registerEvents(listener, VoidAirRace.getInstance());
        }

        return ValueResult.success(new MapSC(gameMap));
    }

    // -------------------- EndableComp --------------------

    @Override
    public @Range(from = 0, to = Integer.MAX_VALUE) int getUninstallPriority() {
        return ComponentPriority.HIGH.getValue();
    }

    @Override
    public @NonNull ValueResult<CustomData> uninstall(
            @NonNull Match match,
            @Nullable CustomData endArg) {
        PlayableGameMap gameMap = match.config().dataOf(MapComp.CONFIG_KEY).map();
        gameMap.over(match);
        if (gameMap instanceof Listener listener) {
            HandlerList.unregisterAll(listener);
        }
        // 卸载成功，不产生结束上下文
        return ValueResult.empty();
    }

    /**
     * @param map 比赛所使用的游戏地图
     * */
    public record MapConfig(@NonNull PlayableGameMap map) implements CustomData {
        @Override
        public @NonNull Class<? extends MatchComp> source() {
            return MapComp.class;
        }
    }

    public record MapECFG(@NonNull NamespacedKey expectedMapId) implements CustomData {
        @Override
        public @NonNull Class<? extends MatchComp> source() {
            return MapComp.class;
        }
    }

    public record MapSC(@NonNull PlayableGameMap gameMap) implements CustomData {
        @Override
        public @NonNull Class<? extends MatchComp> source() {
            return MapComp.class;
        }
    }
}
