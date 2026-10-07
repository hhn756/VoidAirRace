package io.github.hhn756.voidairrace.core.match.componentbase;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.addons.GameElementMeta;
import io.github.hhn756.voidairrace.infrastructure.config.Config;
import io.github.hhn756.voidairrace.infrastructure.config.ConfigDefinition;
import io.github.hhn756.voidairrace.infrastructure.config.FileConfig;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * 比赛组件，用于为比赛扩展功能<br>
 * 比赛配置会在组件实例化时自动向 Bukkit 事件系统注册实现了 {@link org.bukkit.event.Listener} 接口的组件实例<br>
 * 每个组件子类都必须要有一个公开的无参构造器
 * <p>
 * 注：每个比赛配置都会使用不同组件实例，如需跨配置实例共享数据可以使用静态属性或其他类来存储数据
 * */
public class MatchComp {
    private static final @NonNull GameElementMeta defaultMeta = new GameElementMeta(
            new NamespacedKey(VoidAirRace.getInstance(), "default_component"),
            List.of(Component.translatable(TranslateKeys.MatchComp.COMP_BASE_DEFAULT_NAME)),
            List.of(), List.of(), Component.empty(), 0L, List.of(), List.of()
    );

    /**
     * 获取组件的元数据
     * */
    public @NonNull GameElementMeta getMeta() {
        return defaultMeta;
    }

    /**
     * 获取一个配置文件（yaml格式）
     *
     * @param file 目标文件
     *
     * @see Config#getYmlConfig(ConfigDefinition)
     * */
    public <IMPL extends FileConfig> @NonNull IMPL getYmlConfig(@NonNull ConfigDefinition<IMPL> file) {
        return Config.getInstance().getYmlConfig(file);
    }
}
