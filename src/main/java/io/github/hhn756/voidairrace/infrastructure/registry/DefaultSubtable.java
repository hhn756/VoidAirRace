package io.github.hhn756.voidairrace.infrastructure.registry;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.function.Function;

/**
 * 注册项子表的默认实现，记录一个类别下的所有注册项，提供基本的注册与查询能力
 * <p>
 * <strong>非线程安全</strong>：此类设计时不考虑多线程环境
 * <p>
 * 此实现的特性：
 * <p>
 * - 同一类别内注册项的键唯一，重复注册不会覆盖已有项（详见{@link #add(Object)}）
 * <p>
 * - 键的计算是子表的内部行为：默认实现使用构造时注入的键计算函数，从注册项派生键。
 *   注册项类型因此不受任何约束，简单值（如{@code String}）无需包装类型即可注册
 * <p>
 * 使用此实现：
 * <p>
 * - 禁止<strong>直接构造实例</strong>：应通过{@link Registry#category(CategoryId)}获得由注册表维护的实例
 * <p>
 * 需要<strong>更高级行为</strong>（如双射映射）：继承本类并实现自定义功能，然后通过{@link Registry#createCategory(CategoryId, Factory)}注册<br>
 * 此时类别标识的第三个泛型参数绑定到该子类，调用方可在编译期拿到子类的重写方法
 *
 * @param <I> 该子表下所有注册项的类型。无约束，任意类型均可作为注册项
 * @param <K> 此类注册项的主键类型
 */
public class DefaultSubtable<I, K> {
    /**
     * 自定义子表工厂，配合{@link Registry#createCategory(CategoryId, Factory)}
     * 为类别指定其{@link DefaultSubtable}子类实现
     *
     * @param <I> 注册项类型
     * @param <K> 主键类型
     * @param <C> 要创建的子表类型
     */
    @FunctionalInterface
    public interface Factory<I, K, C extends DefaultSubtable<I, K>> {
        /**
         * @param id 要为其创建子表实例的类别标识
         *
         * @return 子表实例，应使用传入的id构造
         */
        @NonNull C create(@NonNull CategoryId<I, K, C> id);
    }

    /**
     * 使用子表实例的类别的标识
     * */
    protected final CategoryId<I, K, ? extends DefaultSubtable<I, K>> id;

    /**
     * 键计算函数，本子表内部行为的组成部分，{@link #add(Object)}用其从注册项派生键
     * */
    protected final Function<I, K> keyFn;

    /**
     * 本子表：键 → 注册项
     */
    protected final Map<K, I> entries = new HashMap<>();

    /**
     * 构造子表实例的基类部分，供默认实现及子类链构造使用
     *
     * @param id    使用子表实例的类别的标识
     * @param keyFn 键计算函数，用于在注册元素时计算新元素的键。输入{@code I}（表实例的注册项类型）；输出对应注册项的键<br>
     *              子类若有其他键来源，可在链构造时传入自身的派生策略
     */
    protected DefaultSubtable(
            @NonNull CategoryId<I, K, ? extends DefaultSubtable<I, K>> id,
            @NonNull Function<I, K> keyFn
    ) {
        this.id = id;
        this.keyFn = keyFn;
    }

    /**
     * @return 使用子表实例的类别的标识
     */
    public @NonNull CategoryId<I, K, ? extends DefaultSubtable<I, K>> getId() {
        return id;
    }

    /**
     * 注册一项到此子表
     * <p>
     * <strong>不覆盖已有项</strong>：若类别中已存在键相同的注册项，则保留原注册项，本次传入的注册项不会被注册
     *
     * @param entry 要注册的注册项
     */
    public void add(@NonNull I entry) {
        // putIfAbsent 保证键唯一且不覆盖已有值
        entries.putIfAbsent(keyFn.apply(entry), entry);
    }

    /**
     * 从此子表中注销键对应的注册项<br>
     * 键在此子表中不存在时，不产生效果
     *
     * @param key 要注销注册项的键
     */
    public void remove(K key) {
        entries.remove(key);
    }

    /**
     * 按键获取此子表中的注册项
     *
     * @param key 查询键
     *
     * @return 注册项，该键未注册时返回{@code null}
     */
    public @Nullable I get(K key) {
        return entries.get(key);
    }

    /**
     * 检查指定键是否已注册<br>
     * 即指定键是否能在此子表中找到对应的、已记录的值
     *
     * @param key 指定键
     *
     * @return 指定项已注册返回{@code true}，否则返回{@code false}
     */
    public boolean isRegistered(K key) {
        return entries.containsKey(key);
    }

    /**
     * 列出此子表下的所有注册项<br>
     * 返回的是快照副本，对其修改不会影响类别内部记录
     *
     * @return 注册项集合，类别中无注册项时返回空集合
     */
    public @NonNull Collection<@NonNull I> list() {
        // 返回副本以避免外部修改内部结构
        return new HashSet<>(entries.values());
    }
}
