package io.github.hhn756.voidairrace.service.datapackregistrar;

import io.github.hhn756.voidairrace.constants.Plugin;
import io.github.hhn756.voidairrace.constants.ResourcePath;
import io.github.hhn756.voidairrace.infrastructure.modules.BootstrapStage;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.net.URISyntaxException;

/**
 * 在 bootstrap 阶段将插件中指定名称的目录作为数据包注册到服务器
 * */
public class DataPackRegistrar implements BootstrapStage {
    @Override
    public void onBootstrap(@NonNull BootstrapContext context) {
        // 注册数据包
        context.getLifecycleManager().registerEventHandler(
                LifecycleEvents.DATAPACK_DISCOVERY,
                event -> {
                    try {
                        event.registrar().discoverPack(
                                getClass().getResource(
                                        ResourcePath.DATAPACK.toString()  // 数据包目录路径
                                ).toURI(),
                                Plugin.ns
                        );
                    } catch (IOException | URISyntaxException | NullPointerException e) {
                        context.getLogger().warn(
                                Component.text("加载数据包失败：" + e.getLocalizedMessage())
                                        .color(NamedTextColor.RED)
                        );
                    }
                }
        );
    }
}
