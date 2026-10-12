package io.github.hhn756.voidairrace.core.matchrule;

import io.github.hhn756.voidairrace.addons.GameElement;
import io.github.hhn756.voidairrace.addons.GameElementMeta;
import org.bukkit.NamespacedKey;
import org.jspecify.annotations.NonNull;

import java.util.function.Supplier;

/**
 * 记录一个比赛规则的注册项，同时也能代表那个规则
 *
 * @param <R> 此注册项所记录的规则类型
 * */
public class RuleEntry<R extends MatchRule> implements GameElement {
    /**
     * 构造比赛规则记录项实例
     *
     * @param meta 此比赛规则的元数据
     * @param supplier 每次对其获取值时返回注册项所代表类型{@link R}的一个新实例
     * */
    public RuleEntry(@NonNull GameElementMeta meta, @NonNull Supplier<R> supplier) {
        this.meta = meta;
        this.supplier = supplier;
    }

    private final @NonNull GameElementMeta meta;
    private final @NonNull Supplier<R> supplier;

    public @NonNull NamespacedKey getKey() {
        return meta.id();
    }

    @Override
    public @NonNull GameElementMeta getElementMeta() {
        return meta;
    }

    public @NonNull R newInstance() {
        return supplier.get();
    }
}
