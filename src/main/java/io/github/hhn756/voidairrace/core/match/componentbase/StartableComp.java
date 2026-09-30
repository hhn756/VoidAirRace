package io.github.hhn756.voidairrace.core.match.componentbase;

import io.github.hhn756.voidairrace.core.match.ComponentPriority;
import io.github.hhn756.voidairrace.core.match.DataKey;
import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.result.ValueResult;
import org.jetbrains.annotations.Range;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 赋予比赛组件在开始游戏时执行自定义操作和增加自定义开始上下文的能力
 *
 * @param <SA> 组件的开始游戏参数类型
 * @param <SC> 组件的开始游戏上下文数据类型
 * */
public interface StartableComp<
        SA extends CustomData,
        SC extends CustomData>
{
    /**
     * 当组件被加载到比赛上时执行一次<br>
     * 注：组件需自行在操作失败时回到操作前状态，见{@link StartableComp#rollback(Match, CustomData, ValueResult, Exception)}<br>
     * 结果约定：安装成功且不产生开始上下文时返回{@link ValueResult#empty()}；
     * 产生了开始上下文时返回{@link ValueResult#success(Object)}；
     * 失败时返回{@link ValueResult#failure(String)}并携带本层失败原因的翻译键
     *
     * @param match 组件实例被加载到的比赛
     * @param startArg 开始游戏方法调用方传入的参数，用于在开始时控制模块的行为。组件可以定义自己的参数格式和行为<br>
     *                     如果调用方没有给特定组件传入此参数，那么组件将收到 {@code null}
     * */
    @NonNull ValueResult<SC> install(@NonNull Match match, @Nullable SA startArg);

    /**
     * 获取比赛开始时加载此组件的优先级，值越大越先加载<br>
     * 此方法返回值应在每个实例、每次调用时都一致
     *
     * @return 组件加载优先级
     * */
    default @Range(from = 0, to = Integer.MAX_VALUE) int getInstallPriority() {
        return ComponentPriority.NORMAL.getValue();
    }

    /**
     * 获取组件的开始上下文数据键<br>
     * 一般情况下此方法应始终返回同一个对象<br>
     * 如果组件没有开始上下文数据将不会实现此方法
     *
     * @see DataKey
     * */
    default @NonNull DataKey<?> getSCK() {
        return DataKey.of(null, null);
    };

    /**
     * 当组件安装失败时调用，用于回滚已执行的操作<br>
     * 默认实现为空，组件可按需覆盖
     *
     * @param match       在其上安装失败的比赛实例
     * @param startArg    传递给{@link StartableComp#install(Match, CustomData)}的参数（可能为{@code null}）
     * @param installResult 安装失败后返回的结果（安装方法抛出异常时可能不存在）
     * @param installException 安装方法抛出的异常（可能不存在）
     */
    default void rollback(@NonNull Match match,
                          @Nullable SA startArg,
                          @Nullable ValueResult<SC> installResult,
                          @Nullable Exception installException) {
        // 默认不做任何操作
    }
}
