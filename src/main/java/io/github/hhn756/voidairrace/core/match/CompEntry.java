package io.github.hhn756.voidairrace.core.match;

import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.match.componentbase.MatchComp;
import io.github.hhn756.voidairrace.exception.UserFriendlyException;
import io.github.hhn756.voidairrace.result.ValueResult;
import org.jspecify.annotations.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/**
 * 用于在注册表中记录一个比赛组件
 * */
public class CompEntry {
    public @NonNull Class<MatchComp> getKey() {
        return compType;
    }

    /**
     * 构造一个记录指定比赛组件的组件注册项对象
     *
     * @param compType 新实例将要代表的比赛组件类型，其必须要有一个无参数的构造器
     *
     * @throws NoSuchMethodException 如果指定组件类型没有无参构造器
     * */
    CompEntry(@NonNull Class<MatchComp> compType) throws NoSuchMethodException {
        this.compType = compType;
        constructor = compType.getConstructor();
    }

    /**
     * 所记录的组件类型
     * */
    private final @NonNull Class<MatchComp> compType;

    /**
     * 所记录组件类型的构造器
     * */
    private final @NonNull Constructor<MatchComp> constructor;

    /**
     * @return 所记录的组件类型
     * */
    public @NonNull Class<MatchComp> getCompType() {
        return compType;
    }

    /**
     * 创建一个此元数据所代表组件类型的新实例
     *
     * @return 新组件实例（成功时携带）；构造失败时携带失败信息：
     *         用户消息为翻译键（异常实现{@link UserFriendlyException}时附带其用户消息），
     *         技术性消息为异常文本，异常原样透传
     */
    public @NonNull ValueResult<MatchComp> newInstance() {
        try {
            MatchComp instance = constructor.newInstance();
            return ValueResult.success(instance);
        } catch (ReflectiveOperationException e) {
            // 反射包装链：InvocationTargetException.getCause()才是组件构造器抛出的原始异常
            Throwable root = (e instanceof InvocationTargetException ite && ite.getCause() != null)
                    ? ite.getCause()
                    : e;
            // 异常文本属于技术性信息，只进技术性消息；异常自带可给玩家的文案时保留
            return ValueResult.failure(
                    TranslateKeys.Match.COMP_ENTRY_INSTANTIATE_FAILURE,
                    root instanceof UserFriendlyException friendly ? friendly.getUserMessage() : null,
                    root.getClass().getSimpleName() + ": " + root.getMessage(),
                    root
            );
        }
    }
}
