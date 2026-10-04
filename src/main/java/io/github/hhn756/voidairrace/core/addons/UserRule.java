package io.github.hhn756.voidairrace.core.addons;

import io.github.hhn756.voidairrace.core.addons.usrpackage.UsrPackage;
import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.core.matchrule.MatchRule;
import io.github.hhn756.voidairrace.core.matchrule.RuleEntry;
import io.github.hhn756.voidairrace.result.OperationResult;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 包装一个由用户包注册的比赛规则<br>
 * 用户包在脚本侧描述的规则由此类适配为 {@link MatchRule}：
 * 元数据与标签来自注册时提供的信息，规则行为委托给注册项适配出的{@link Callback 回调}
 * <p>
 * 本类型是所有用户规则的统一类型：不同规则的差异完全由构造参数（元数据、标签、回调）表达，
 * 不存在也不需要子类，其注册项固定产出本类型
 * <p>
 * 实例化约束：只能由本规则对应的注册项构造（构造器包私有），其余代码不应也无法实例化本类
 * */
public final class UserRule implements MatchRule {
    private final @NonNull GameElementMeta meta;
    private final @NonNull Set<@NonNull String> tags;
    private final @NonNull UsrPackage source;
    private final @Nullable Callback callback;

    /**
     * 构造一个用户规则实例<br>
     * 仅限本规则对应的注册项调用
     *
     * @param meta     规则的元数据，来自注册时提供的信息
     * @param tags     规则的标签，内部存储为不可变副本
     * @param source   注册此规则的来源用户包
     * @param callback 规则的行为回调，允许为 {@code null}（表示无自定义行为，各生命周期方法采用默认行为）
     * */
    UserRule(
            @NonNull GameElementMeta meta,
            @NonNull Collection<@NonNull String> tags,
            @NonNull UsrPackage source,
            @Nullable Callback callback
    ) {
        this.meta = meta;
        this.tags = Set.copyOf(tags);
        this.source = source;
        this.callback = callback;
    }

    /**
     * 构造一个用户规则注册项<br>
     * 本方法是全项目唯一调用{@link UserRule}构造器的位置：注册项的工厂在每次
     * {@link RuleEntry#newInstance()} 时用注册参数构造一个全新的 UserRule 实例，
     * 规则实例化因此只能经注册项发生
     *
     * @param meta      规则的元数据，来自注册时提供的信息
     * @param tags      规则的标签，内部存储为不可变副本
     * @param source    注册此规则的来源用户包
     * @param callbacks 回调工厂，每个规则实例构造时调用一次以获得该实例专属的行为回调
     *
     * @return 新的用户规则注册项
     * */
    public static @NonNull RuleEntry<UserRule> entry(
            @NonNull GameElementMeta meta,
            @NonNull Collection<@NonNull String> tags,
            @NonNull UsrPackage source,
            @NonNull Supplier<@NonNull Callback> callbacks
    ) {
        return new RuleEntry<>(meta, () -> new UserRule(meta, tags, source, callbacks.get()));
    }

    /**
     * @return 注册此规则的来源用户包
     * */
    public @NonNull UsrPackage source() {
        return source;
    }

    @Override
    public @NonNull GameElementMeta getElementMeta() {
        return meta;
    }

    @Override
    public @NonNull Collection<String> getTags() {
        return tags;
    }

    @Override
    public @NonNull OperationResult onEnable(@NonNull Match match) {
        if (callback == null) return OperationResult.success();
        return callback.onEnable(match);
    }

    @Override
    public void onDisable(@NonNull Match match) {
        if (callback != null) callback.onDisable(match);
    }

    @Override
    public void tick(@NonNull Match match) {
        if (callback != null) callback.tick(match);
    }

    /**
     * 用户规则的行为回调<br>
     * 注册项负责把用户包脚本提供的规则行为适配为本接口；
     * 各方法的默认行为与 {@link MatchRule} 一致，脚本未提供的行为可直接继承默认实现
     * */
    public interface Callback {
        /**
         * 规则被启用时调用（例如比赛开始时或中途添加）
         *
         * @param match 当前比赛实例
         *
         * @return 如果返回失败的结果那么将会取消这次启用规则操作
         * */
        default @NonNull OperationResult onEnable(@NonNull Match match) {
            return OperationResult.success();
        }

        /**
         * 规则被禁用时调用（例如比赛结束或中途移除）
         * */
        default void onDisable(@NonNull Match match) {}

        /**
         * 规则加载时每游戏刻自动执行一次（注意：避免包含耗时操作）
         * */
        default void tick(@NonNull Match match) {}
    }
}
