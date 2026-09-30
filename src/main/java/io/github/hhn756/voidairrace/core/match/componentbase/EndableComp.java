package io.github.hhn756.voidairrace.core.match.componentbase;

import io.github.hhn756.voidairrace.core.match.ComponentPriority;
import io.github.hhn756.voidairrace.core.match.DataKey;
import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.result.ValueResult;
import org.jetbrains.annotations.Range;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 赋予比赛组件在结束游戏时执行自定义操作和增加自定义结束上下文的能力
 *
 * @param <EA> 组件的结束游戏参数类型
 * @param <EC> 组件的结束游戏上下文数据类型
 * */
public interface EndableComp<
        EA extends CustomData,
        EC extends CustomData>
{
    /**
     * 当比赛卸载此组件时执行一次<br>
     * 组件实现的操作发生错误时应自行解决或输出错误日志<br>
     * 结果约定：卸载成功且不产生结束上下文时返回{@link ValueResult#empty()}；
     * 产生了结束上下文时返回{@link ValueResult#success(Object)}；
     * 失败时返回{@link ValueResult#failure(String)}并携带本层失败原因的翻译键
     *
     * @param match 组件实例被卸载前所在的比赛
     * @param endArg 结束游戏方法调用方传入的参数，用于在结束时控制模块的行为。组件可以定义自己的参数格式和行为<br>
     *                   如果调用方没有给特定组件传入此参数，那么组件将收到 {@code null}
     * */
    @NonNull ValueResult<EC> uninstall(@NonNull Match match, @Nullable EA endArg);

    /**
     * 获取比赛结束时卸载此组件的优先级，值越大越先卸载<br>
     * 此方法返回值应在每个实例、每次调用时都一致
     *
     * @return 组件卸载优先级
     * */
    default @Range(from = 0, to = Integer.MAX_VALUE) int getUninstallPriority() {
        return ComponentPriority.NORMAL.getValue();
    }

    /**
     * 获取组件的结束上下文数据键<br>
     * 一般情况下此方法应始终返回同一个对象<br>
     * 如果组件没有自定义结束上下文将不会实现此方法
     *
     * @see DataKey
     * */
    default @NonNull DataKey<?> getECK() {
        return DataKey.of(null, null);
    };
}
