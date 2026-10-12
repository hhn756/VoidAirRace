package io.github.hhn756.voidairrace.infrastructure.modules;

import org.bukkit.event.Listener;
import org.jspecify.annotations.NonNull;

import java.util.Collection;

/**
 * 模块的 Bukkit 事件监听器声明契约：实现本接口的模块，其 {@code getListeners()} 返回的监听器
 * 会在该模块 {@code onLoad} 成功后由 {@link Modules} 注册到 Bukkit，并在模块卸载或加载回滚时反注册<br>
 * 注册发生在模块加载流程内：任一监听器注册失败视同模块加载失败，触发整体回滚
 *
 * <p>监听器实例由模块自行构造（构造参数不受限），声明的责任归属即注册的归属：
 * 同一监听器实例只应由一个模块声明，否则会重复注册
 * <p>
 * 注意：本契约只适用于插件启用阶段——引导阶段（{@link BootstrapStage}）不能也不需要注册 Bukkit 事件，
 * 引导阶段任务不提供同款能力
 * */
public interface ListenerProvider {

    /**
     * 声明本模块拥有的 Bukkit 事件监听器
     *
     * @return 监听器集合，可为空集合；实现不应返回 null（防御性处理：返回 null 时按无监听器处理并记录警告）
     * */
    @NonNull Collection<@NonNull Listener> getListeners();
}
