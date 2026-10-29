package io.github.hhn756.voidairrace.core.map;

import io.github.hhn756.voidairrace.Voidairrace;
import io.github.hhn756.voidairrace.constants.Categories;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.infrastructure.config.Config;
import io.github.hhn756.voidairrace.infrastructure.config.YamlConfig;
import io.github.hhn756.voidairrace.infrastructure.config.file.PublicFile;
import io.github.hhn756.voidairrace.infrastructure.language.ServerLanguage;
import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import io.github.hhn756.voidairrace.infrastructure.registry.Registry;
import io.github.hhn756.voidairrace.infrastructure.util.schedulingutil.SchedulingUtil;
import io.github.hhn756.voidairrace.infrastructure.util.world.blockfinder.BlockFinder;
import org.bukkit.NamespacedKey;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MapInitializer implements Module {
    private static @Nullable MapInitializer instance;

    public static @NonNull MapInitializer getInstance() throws NullPointerException {
        if (instance == null) throw new NullPointerException("地图初始化器实例不存在");
        return instance;
    }

    // ------

    private @NonNull Voidairrace mainClass;
    private @NonNull YamlConfig flagConfig;
    private @NonNull Logger logger;

    private MapInitializer() {}

    @Override
    public @NonNull Collection<@NonNull Class<? extends Module>> getRequiredModules() {
        // BlockFinder 虽无直接引用，但地图初始化流程（initAllMapsAsync 触发的各地图 initAsync）会用到
        return List.of(Config.class, Registry.class, SchedulingUtil.class, BlockFinder.class);
    }

    /**
     * 插件启用时执行<br>
     * 依赖解析等有副作用的工作必须在此完成：Modules 会先实例化全部模块，再按拓扑顺序加载
     * */
    private void onLoad() {
        flagConfig = Config.getInstance().getYmlConfig(PublicFile.FLAG);
        mainClass = Voidairrace.getInstance();
        logger = mainClass.getLogger();

        instance = this;

        // 定义“游戏地图”类别，键计算：注册项元数据中的地图id
        Registry.getInstance().createCategory(Categories.MAP, MapEntry::getKey);
        // 初始化所有地图
        initAllMapsAsync();
    }

    /** 插件禁用时执行 */
    private void onUnload() {
        instance = null;
    }

    /**
     * 异步初始化所有未初始化的地图（按顺序）<br>
     * 该方法立即返回，初始化在后台进行
     */
    public void initAllMapsAsync() {
        // 获取要初始化的地图
        Collection<MapEntry> maps = Registry.getInstance().category(Categories.MAP).list();

        // 初始化任务
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

        for (MapEntry meta : maps) {
            NamespacedKey mapId = meta.getElementMeta().id();
            if (isInited(mapId)) continue;

            // 链接初始化任务，确保顺序执行
            chain = chain.thenCompose(ignored -> initOneMap(mapId));
        }

        chain.thenRun(() -> logger.fine(
                ServerLanguage.translate(TranslateKeys.Log.MAP_INITIALIZER_ALL_FINISHED)));
    }

    /**
     * 重新初始化指定地图（异步）
     *
     * @param mapId 目标地图的 ID
     *
     * @return 表示初始化完成的 future
     *
     * @throws IllegalArgumentException 当指定地图不存在时抛出
     */
    public @NonNull CompletableFuture<Void> reinitMap(@NonNull NamespacedKey mapId) throws IllegalArgumentException {
        GameMap mapInst = Registry.getInstance().category(Categories.MAP).get(mapId).newInstance();

        // 如果地图之前已初始化，先执行同步清理
        if (isInited(mapId)) {
            mapInst.unInit();
            // 立即清除标志（即使 unInit 可能耗时，但它是同步的）
            setInitFlag(mapId, false);
        }

        // 执行异步初始化并返回 future
        return initOneMap(mapId);
    }

    /**
     * 初始化单张地图（异步），不检查当前是否已初始化<br>
     * 调用者应确保适当的时机调用（例如在 initAllMapsAsync 中已过滤未初始化地图在 reinitMap 中已清除标志）
     *
     * @param mapId 地图 ID
     *
     * @return 表示初始化完成的 future
     */
    private @NonNull CompletableFuture<Void> initOneMap(@NonNull NamespacedKey mapId) {
        GameMap mapInst = Registry.getInstance().category(Categories.MAP).get(mapId).newInstance();
        try {
            return mapInst.initAsync(mainClass)
                    .thenRunAsync(() -> {
                                setInitFlag(mapId, true);
                                logger.fine(ServerLanguage.translate(
                                        TranslateKeys.Log.MAP_INITIALIZER_MAP_FINISHED, mapId));
                            },
                            SchedulingUtil::runOnMainThread
                    )
                    .exceptionally(throwable -> {
                        logger.log(
                                Level.SEVERE,
                                ServerLanguage.translate(
                                        TranslateKeys.Log.MAP_INITIALIZER_MAP_FAILED, mapId, throwable.getMessage()),
                                throwable);
                        // 失败时不设置标志，下次重启或手动重试会再次尝试
                        return null;
                    });
        } catch (NullPointerException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 获取地图初始化状态
     *
     * @return `true`表示已初始化，`false`表示未初始化
     */
    public boolean isInited(@NonNull NamespacedKey mapId) {
        return flagConfig.getBoolean(mapIdToFlagPath(mapId), false);
    }

    /**
     * 修改地图初始化状态
     * 注：这只会修改标记而不会进行初始化操作
     */
    public void setInitFlag(@NonNull NamespacedKey mapId, boolean initFlag) {
        flagConfig.set(mapIdToFlagPath(mapId), initFlag);
    }

    /**
     * @param mapId 指定地图的ID
     *
     * @return 用于标识指定地图初始化状态的配置键的路径
     * */
    private @NonNull String mapIdToFlagPath(@NonNull NamespacedKey mapId) {
        return "mapInitState." + mapId;
    }
}
