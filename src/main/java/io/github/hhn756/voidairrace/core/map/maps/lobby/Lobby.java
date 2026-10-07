package io.github.hhn756.voidairrace.core.map.maps.lobby;

import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.addons.GameElementMeta;
import io.github.hhn756.voidairrace.core.map.GameMap;
import net.kyori.adventure.text.Component;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import javax.lang.model.type.NullType;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class Lobby extends GameMap implements Listener {
    private static final GameElementMeta meta = new GameElementMeta(
            Data.MAP_ID,
            List.of(
                    Component.translatable(TranslateKeys.Map.LOBBY_NAME)
            ),
            List.of(
                    Component.translatable(TranslateKeys.Map.LOBBY_DESCRIPTION_LINE1)
            ),
            List.of(
                    Component.translatable(TranslateKeys.Map.LOBBY_AUTHOR1)
            ),
            Component.translatable(TranslateKeys.Map.LOBBY_DISPLAY_VERSION),
            1L,
            List.of(),
            List.of()
    );

    @Override
    public @NonNull CompletableFuture<NullType> initAsync(JavaPlugin mainClass) {
        Renderer.reRenderAll();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public @NonNull GameElementMeta getElementMeta() {
        return meta;
    }
}
