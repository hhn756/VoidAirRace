package io.github.hhn756.voidairrace;

import io.github.hhn756.voidairrace.infrastructure.config.Config;
import io.github.hhn756.voidairrace.infrastructure.config.files.GlobalSettingKeys;
import io.github.hhn756.voidairrace.infrastructure.config.files.PublicFiles;
import io.github.hhn756.voidairrace.infrastructure.listenerregistrar.ListenerRegistrar;
import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import io.github.hhn756.voidairrace.infrastructure.modules.Modules;
import io.github.hhn756.voidairrace.infrastructure.util.ClassScanner;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.logging.Logger;

/**
 * 插件主类
 * */
public class VoidAirRace extends JavaPlugin {
    @Override
    public void onEnable() {
        instance = this;

        // 加载所有模块
        Modules.loadAll(ClassScanner.scanSubclasses(Module.class));

        // 文字画 “VAR”（纯装饰，可经 global_settings.hide_ascii_logo 关闭）
        if (!Config.getInstance()
                .getYmlConfig(PublicFiles.GLOBAL_SETTINGS)
                .get(GlobalSettingKeys.HIDE_ASCII_LOGO)
        ) {
            Logger logger = getLogger();
            logger.info(" __     ___    ____  ");
            logger.info(" \\ \\   / / \\  |  _ \\ ");
            logger.info("  \\ \\ / / _ \\ | |_) |");
            logger.info("   \\ V / ___ \\|  _ < ");
            logger.info("    \\_/_/   \\_\\_| \\_\\");
        }

        // 注册所有 Bukkit 事件监听器
        ListenerRegistrar.RegisterAll(this);
    }

    @Override
    public void onDisable() {
        getLogger().fine("禁用中...");

        // 按加载逆序卸载模块链
        Modules.unloadAll();

        instance = null;
    }

    /**
     * 插件本次启用中的主类实例
     * */
    private static @Nullable VoidAirRace instance;

    /**
     * 用于获取插件主类实例，方便访问公共资源
     *
     * @return 主类实例
     *
     * @throws NullPointerException 主类实例不存在时抛出，正常情况下无法触发
     * */
    // 虽然禁用后为null，但 其他部分被禁止在插件禁用时运行 且 插件启用后首先会更新变量值，所以其他部分不可能获取到 null
    public static @NonNull VoidAirRace getInstance() throws NullPointerException {
        if (instance == null) throw new NullPointerException("插件主类实例不存在");
        return instance;
    }
}
