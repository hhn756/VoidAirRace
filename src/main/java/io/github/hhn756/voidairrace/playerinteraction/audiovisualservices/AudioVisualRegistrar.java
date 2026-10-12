package io.github.hhn756.voidairrace.playerinteraction.audiovisualservices;

import io.github.hhn756.voidairrace.infrastructure.modules.ListenerProvider;
import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import org.bukkit.event.Listener;
import org.jspecify.annotations.NonNull;

import java.util.Collection;
import java.util.List;

/**
 * 音频视觉服务注册模块：集中持有并登记本包内全部音频视觉服务的 Bukkit 事件监听器
 * */
public class AudioVisualRegistrar implements Module, ListenerProvider {
    @Override
    public @NonNull Collection<@NonNull Class<? extends Module>> getRequiredModules() {
        return List.of();
    }

    @Override
    public @NonNull Collection<@NonNull Listener> getListeners() {
        return List.of(new MatchMessage(), new LoginMessage(), new GameTimeBossbar());
    }
}
