package io.github.hhn756.voidairrace.core.map;

import io.github.hhn756.voidairrace.addons.GameElement;
import io.github.hhn756.voidairrace.addons.GameElementMeta;
import io.github.hhn756.voidairrace.exception.RegistryException;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.Range;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

/**
 * 用于在注册表中记录一个地图
 * */
public class MapEntry implements GameElement {
    /** 地图的元数据 */
    private final GameElementMeta elementMeta;
    /** 地图类型对象的实例化方式 */
    private final Supplier<? extends GameMap> mapFactory;
    /** 地图是否可游玩（被比赛使用） */
    private final boolean playable;
    /** 最大参赛队伍数量 */
    private final Integer maxTeams;

    /**
     * 构造一个游戏地图注册项
     *
     * @param elementMeta 此地图的元数据
     * @param mapFactory 此地图的实例化方式（每次调用应返回全新的地图实例），{@code playable}为{@code true}时应返回{@link PlayableGameMap}；{@code playable}为{@code false}时应返回{@link GameMap}（此约定在{@link #newInstance()}时校验）
     * @param playable 地图是否可游玩
     *
     * @throws RegistryException 如果{@code playable}为{@code true}但却未传入最大队伍数量
     * */
    public MapEntry(
            @NonNull GameElementMeta elementMeta,
            @NonNull Supplier<? extends GameMap> mapFactory,
            boolean playable,
            @Nullable @Range(from = 1, to = Integer.MAX_VALUE) Integer maxTeams
    ) throws RegistryException {
        if (playable && maxTeams == null) {
            throw new RegistryException("地图 “" + elementMeta.id() + "” 被声明为可游玩的，应传入最大队伍数量但却未传入", null);
        }
        this.elementMeta = elementMeta;
        this.mapFactory = mapFactory;
        this.playable = playable;
        this.maxTeams = maxTeams;
    }

    public @NonNull NamespacedKey getKey() {
        return elementMeta.id();
    }

    @Override
    public @NonNull GameElementMeta getElementMeta() {
        return elementMeta;
    }

    /**
     * 获取此地图的新实例
     *
     * @return 此地图的新实例，{@link MapEntry#isPlayable()}为{@code true}时将返回{@link PlayableGameMap}，否则返回{@link GameMap}
     *
     * @throws RegistryException 如果实例化过程抛出了异常
     * */
    public @NonNull GameMap newInstance() throws RegistryException {
        GameMap instance;
        try {
            instance = mapFactory.get();
        } catch (Exception e) {
            // 仅转义异常
            throw new RegistryException("实例化游戏地图失败：" + e.getLocalizedMessage(), null);
        }
        // 实例化方式约定校验：此处产物类型真实可知（Supplier 无法静态检查）
        if (playable && !(instance instanceof PlayableGameMap)) {
            throw new RegistryException("地图 “" + elementMeta.id() + "” 被声明为可游玩的，实例化方式返回的对象类型也应是“可游玩地图”，实际却不是此类型", null);
        }
        return instance;
    }

    /**
     * 在不实例化地图的情况下检查地图是否可玩
     *
     * @return 如果地图可游玩将返回{@code true}，否则返回{@code false}
     * */
    public boolean isPlayable() {
        return playable;
    }

    /**
     * 获取地图允许参赛的最大队伍数量
     *
     * @return 最大队伍数量
     * */
    @Range(from = 1, to = Integer.MAX_VALUE)
    public @Nullable Integer maxTeams() {
        return maxTeams;
    }
}
