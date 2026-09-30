package io.github.hhn756.voidairrace.core.match.componentbase;

import org.jspecify.annotations.NonNull;

/**
 * 代表组件提供的自定义数据，例如自定义配置、期望配置、开始/结束参数等<br>
 * 需要实现类提供实际字段。使用组件的自定义数据时应声明实现类型的变量以访问实际字段
 * */
public interface CustomData {
    /**
     * 获取添加此自定义数据类型的组件，通过此方法将自定义数据和其父组件关联在一起<br>
     * 一般情况下，此方法在每个实例、每次调用的返回值都应一致
     *
     * @return 添加此自定义数据类型的组件
     * */
    @NonNull Class<? extends MatchComp> source();
}
