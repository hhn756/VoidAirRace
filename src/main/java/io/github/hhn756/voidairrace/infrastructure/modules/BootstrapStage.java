package io.github.hhn756.voidairrace.infrastructure.modules;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import org.jspecify.annotations.NonNull;

/**
 * 引导阶段任务接口（旧称“bootstrap模块”）<br>
 * 实现此接口的类会在插件引导阶段（服务端加载插件本体之前）被 {@link Modules#bootstrapAll(BootstrapContext)}
 * 扫描并执行一次 {@code onBootstrap}，与插件启用阶段由 {@link Modules#loadAll} 加载的“普通模块”{@link Module}
 * 是两种互不相干的概念，仅共用同一个加载器
 * <p>
 * 实现类必须提供一个无参构造器（访问权限任意，包括 private）
 * */
public interface BootstrapStage {
    /**
     * 插件引导阶段执行一次
     *
     * @param context 引导上下文
     * */
    void onBootstrap(@NonNull BootstrapContext context);
}
