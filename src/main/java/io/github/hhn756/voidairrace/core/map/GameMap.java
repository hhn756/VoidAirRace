package io.github.hhn756.voidairrace.core.map;

import io.github.hhn756.voidairrace.constants.Plugin;
import io.github.hhn756.voidairrace.core.addons.GameElement;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * 游戏地图的基类<br>
 * 定义了获取地图基本信息和地图初始化等方法
 * */
public abstract class GameMap implements GameElement {
    /**
     * 子类未指定其ID时方法所用的默认地图ID<br>
     * 系统保留此ID，不可被任何地图使用
     * */
    public static final NamespacedKey DEFAULT_MAP_ID = Plugin.key("game_map_default_id");

    /**
     * 插件首次启用或执行重新初始化命令时执行<br>
     * 此方法在主线程执行
     *
     * @return 当返回的 {@link CompletableFuture} 正常完成时，地图将被标记为“已初始化”
     * */
    public @NonNull CompletableFuture<?> initAsync(JavaPlugin mainClass) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 用于回到初始化前的状态<br>
     * <br>
     * 重新初始化地图时，会先执行此方法然后执行{@link GameMap#initAsync(JavaPlugin)}<br>
     * 注：需通过{@link MapInitializer#reinitMap(NamespacedKey)}重初始化，否则此方法不会被自动调用
     * */
    void unInit() {}

    /**
     * 检查地图的初始化状态
     *
     * @return 如果地图已初始化将返回{@code true}，否则返回{@code false}
     * */
    public boolean isInited() {
        return MapInitializer.getInstance().isInited(DEFAULT_MAP_ID);
    }

    /**
     * 获取一个属于指定游戏地图的配置文件的路径，不含后缀名、用{@code /}划分层级
     *
     * @param mapId 指定地图
     * @param filePath 指定文件相对地图配置目录的路径，不含后缀名、用{@code /}划分层级
     * */
    public static @NonNull String configPath(@NonNull NamespacedKey mapId, @NonNull String filePath) {
        return "maps/"
                + mapId.getNamespace()
                + "/"
                + mapId.getKey()
                + "/"
                + filePath;
    }

    /**
     * 获取一个属于此地图的在插件JAR内资源的路径
     *
     * @param path 资源路径（含后缀名），相对地图目录
     *
     * @return {@code path}不为{@code null}时完整资源路径（以 {@code /} 开头），否则返回地图资源目录路径（以 {@code /} 开头）
     * */
    public @NonNull String resourcePath(@Nullable String path) {
        return "/maps/" + getElementMeta().id() + "/" + path;
    }
}
