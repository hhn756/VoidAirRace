package io.github.hhn756.voidairrace.core.match.componentbase;

import io.github.hhn756.voidairrace.core.match.ComponentPriority;
import io.github.hhn756.voidairrace.core.match.DataKey;
import io.github.hhn756.voidairrace.core.match.MatchConfig;
import io.github.hhn756.voidairrace.result.ValueResult;
import org.jetbrains.annotations.Range;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 赋予比赛组件增加自定义比赛配置数据的能力
 *
 * @param <ECFG> 组件的预期配置类型
 * @param <CFG> 组件的自定义配置类型
 * */
public interface ConfigurableComp<
        ECFG extends CustomData,
        CFG extends CustomData>
{
    /**
     * 获取该组件用来存储配置数据的 Key<br>
     * 如果组件没有配置数据将不会实现此方法
     *
     * @return 该组件用来存储配置数据的 Key
     * */
    default DataKey<CFG> getConfigKey() {
        return DataKey.of(null, null);
    };

    /**
     * 创建一个由该组件提供的自定义配置数据对象
     *
     * @param expected 调用者在创建比赛配置创建时传入的它期望的配置内容。当此值为{@code null}时推荐回退到{@link ConfigurableComp#createDefaultConfig()}而不是返回失败结果
     *
     * @return 配置创建结果。约定见{@link ConfigurableComp#createDefaultConfig()}；
     *         默认实现直接转发{@link ConfigurableComp#createDefaultConfig()}的结果（即忽略预期配置）
     * */
    default @NonNull ValueResult<CFG> createCustomConfig(@Nullable ECFG expected) {
        // 默认忽略预期配置
        return createDefaultConfig();
    }

    /**
     * 根据当前系统状态（配置文件、其他模块状态等）创建一个由该组件提供的自定义比赛配置数据对象（简称“默认配置”/“默认比赛配置”）<br>
     * 结果约定：
     * <ul>
     *     <li>组件实现了本接口即承诺会提供配置数据，成功时用{@link ValueResult#success(Object)}携带配置；
     *     {@link ValueResult.Empty}仅保留给确实无法提供数据的实现（{@link MatchConfig}会把它按失败处理）；</li>
     *     <li>失败时返回{@link ValueResult#failure(String)}并携带本层失败原因的翻译键。</li>
     * </ul>
     *
     * @return 默认配置创建结果
     * */
    @NonNull ValueResult<CFG> createDefaultConfig();

    // ------ 操作优先级控制 ------

    /**
     * 获取比赛配置创建时此组件向其添加自定义数据的优先级，值越大越先添加数据<br>
     * 一般情况下，此方法返回值应在每个实例、每次调用时都一致
     *
     * @return 添加配置优先级
     * */
    default @Range(from = 0, to = Integer.MAX_VALUE) int getConfigPriority() {
        return ComponentPriority.NORMAL.getValue();
    }
}
