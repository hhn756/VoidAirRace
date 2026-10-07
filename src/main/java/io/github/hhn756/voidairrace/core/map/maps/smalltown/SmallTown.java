package io.github.hhn756.voidairrace.core.map.maps.smalltown;

import io.github.hhn756.voidairrace.constants.Plugin;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.addons.GameElementMeta;
import io.github.hhn756.voidairrace.core.map.PlayableGameMap;
import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.result.OperationResult;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.Range;
import org.jspecify.annotations.NonNull;

import java.util.List;

public class SmallTown extends PlayableGameMap {
    private static final NamespacedKey MAP_ID = Plugin.key("small_town");
    private static final GameElementMeta meta = new GameElementMeta(
            MAP_ID,
            List.of(
                    Component.translatable(TranslateKeys.Map.SMALL_TOWN_NAME)
            ),
            List.of(
                    Component.translatable(TranslateKeys.Map.SMALL_TOWN_DESCRIPTION_LINE1)
            ),
            List.of(
                    Component.translatable(TranslateKeys.Map.SMALL_TOWN_AUTHOR1)
            ),
            Component.translatable(TranslateKeys.Map.SMALL_TOWN_DISPLAY_VERSION),
            1L,
            List.of(),
            List.of()
    );

    @Override
    public boolean isReady() {
        return false;
    }

    @Override
    public @NonNull OperationResult start(@NonNull Match match) {
        Bukkit.getServer().broadcast(Component.text("[debug] 已使用 小镇 开始比赛"));
        return OperationResult.success();
    }

    @Override
    public @NonNull OperationResult over(@NonNull Match match) {
        Bukkit.getServer().broadcast(Component.text("[debug] 已使用 小镇 结束比赛"));
        return OperationResult.success();
    }

    @Override
    public @Range(from = 1, to = Integer.MAX_VALUE) int maxTeams() {
        return 0;
    }

    @Override
    public @NonNull GameElementMeta getElementMeta() {
        return meta;
    }
}
