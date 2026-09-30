package io.github.hhn756.voidairrace.infrastructure.modules;

import java.util.Collection;

/**
 * 模块接口，实现此接口的类称为“模块”<br>
 * 每个模块类在一次插件启用中只会被实例化一次（单例），实例由 {@link Modules} 通过反射调用无参构造器创建<br>
 * 注：此处的“模块”概念只是为了方便使用{@link Modules}的功能，
 * 仅在{@link Modules}使用处使用此概念，不代表插件全局范围内通用的“模块”概念
 * <p>
 * 模块实现类还必须满足：
 * <ul>
 *   <li>提供一个无参构造器（访问权限任意，包括 private）</li>
 *   <li>自行声明（不包括继承而来的）恰好一个 {@code private void onLoad(...)}
 *       与恰好一个 {@code private void onUnload(...)}</li>
 * </ul>
 * <p>
 * {@code onLoad} / {@code onUnload} 的参数列表任意，但每个参数的类型必须是本模块某个前置模块
 * 类型的父类或接口；加载器会把对应的已实例化前置模块作为实参传入。借此把“前置关系”与
 * “实际依赖的参数”分离：声明为前置只用于确定加载顺序，方法签名才决定注入哪些实例
 * */
public interface Module {
    /**
     * 返回此模块的前置模块类集合。
     * 集合中的每个元素必须是一个实现了 Module 接口的类。
     * */
    Collection<Class<? extends Module>> getRequiredModules();
}
