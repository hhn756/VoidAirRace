package io.github.hhn756.voidairrace;

import io.github.hhn756.voidairrace.infrastructure.modules.Modules;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import org.jspecify.annotations.NonNull;

public class Bootstrap implements PluginBootstrap {
    /**
     * 引导各模块
     * */
    @Override
    public void bootstrap(@NonNull BootstrapContext context) {
        Modules.bootstrapAll(context);
    }
}
