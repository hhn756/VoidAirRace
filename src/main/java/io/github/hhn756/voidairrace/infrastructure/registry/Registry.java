package io.github.hhn756.voidairrace.infrastructure.registry;

import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import org.jspecify.annotations.NonNull;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 注册表，记录并维护所有类别的子表<br>
 * 注意，<strong>非线程安全</strong>：此类设计时不考虑多线程环境
 * <p>
 * 概念：
 * <p>
 * - 类别：区分不同种类注册项的维度，例如道具和地图<br>
 * - 子表：实际记录注册项的部分<br>
 * - 注册项：被记录在子表中的项，包含具体字段
 * <p>
 * 用法：
 * <p>
 * 1. 通过{@link #getInstance()}取得单例<br>
 * 2. 通过{@link #createCategory(CategoryId, Function)}（默认子表）
 * 或{@link #createCategory(CategoryId, DefaultSubtable.Factory)}（自定义子表）定义类别<br>
 * 3. 调用{@link #category(CategoryId)}传入{@link CategoryId}常量获取目标类别的子表，
 * 再调用子表的{@link DefaultSubtable#add}、{@link DefaultSubtable#get}等方法完成注册项的记录与查询
 * <p>
 * 所有类别必须先定义后使用。键的计算属于子表的内部行为：
 * 默认子表在定义时接收键计算函数，从注册项派生键；自定义子表自行决定键的来源。
 * 注册项类型不受约束，简单值（如{@code String}）无需包装类型即可注册
 */
public class Registry implements Module {
    private static Registry instance;

    // 类别标识 → 子表实例
    private final Map<CategoryId<?, ?, ?>, DefaultSubtable<?, ?>> subtables = new HashMap<>();

    @Override
    public Collection<Class<? extends Module>> getRequiredModules() {
        return List.of();
    }

    /** 插件启用时执行 */
    private void onLoad() {
        instance = this;
    }

    /** 插件停用时执行 */
    private void onUnload() {
        instance = null;
    }

    /**
     * @return 注册表单例对象
     *
     * @throws NullPointerException 如果注册表实例不存在
     */
    public static @NonNull Registry getInstance() throws NullPointerException {
        if (instance == null) throw new NullPointerException("注册表实例不存在");
        return instance;
    }

    // --------------------------------

    private Registry() {}

    /**
     * 创建一个使用默认实现的类别，并指定键计算函数<br>
     * 创建后，该类别独立记录自己名下的注册项，与其他类别互不影响；
     * 向其添加的键由子表内部通过{@code keyFn}自动计算
     * <p>
     * 若类别已创建过，则无操作
     *
     * @param id    要创建的类别标识，其子表类型参数写默认实现{@link DefaultSubtable}
     * @param keyFn 键计算函数，将注册项映射为其键
     *
     * @return 新创建的或原先已存在的指定类别子表实例
     */
    @SuppressWarnings("unchecked")
    public <I, K> @NonNull DefaultSubtable<I, K> createCategory(
            @NonNull CategoryId<I, K, DefaultSubtable<I, K>> id,
            @NonNull Function<I, K> keyFn
    ) {
        return (DefaultSubtable<I, K>) subtables.computeIfAbsent(id, k -> new DefaultSubtable<>(id, keyFn));
    }

    /**
     * 创建一个使用<strong>自定义子表实现</strong>的类别<br>
     * 适用于需要超出基本记录与查询能力的类别（如双射映射）
     * <p>
     * 若类别已创建过，则无操作（已存在的子表不会被工厂创建的新实例替换）
     *
     * @param id      要创建的类别标识，其子表类型参数为自定义实现
     * @param factory 创建类别子表实例的工厂，需使用传入的id构造实例
     *
     * @return 新创建的或原先已存在的指定类别子表实例
     */
    @SuppressWarnings("unchecked")
    public <I, K, C extends DefaultSubtable<I, K>> @NonNull C createCategory(
            @NonNull CategoryId<I, K, C> id,
            DefaultSubtable.@NonNull Factory<I, K, C> factory
    ) {
        return (C) subtables.computeIfAbsent(id, k -> factory.create(id));
    }

    /**
     * 获取指定类别的子表，用于对该类别的注册项进行记录和查询
     * <p>
     * 返回实例的类型由{@code id}的子表类型参数在编译期确定
     * <p>
     * 本方法不自动创建类别（注册表无法凭空获知键的计算方式），
     * 类别必须先通过{@link #createCategory(CategoryId, Function)}或
     * {@link #createCategory(CategoryId, DefaultSubtable.Factory)}创建
     *
     * @param id 指定类别标识
     *
     * @return 类别子表实例，类型为id声明的子表类型
     *
     * @throws IllegalStateException 如果类别尚未创建
     */
    @SuppressWarnings("unchecked")
    public <I, K, C extends DefaultSubtable<I, K>> @NonNull C category(
            @NonNull CategoryId<I, K, C> id
    ) {
        DefaultSubtable<?, ?> subtable = subtables.get(id);
        if (subtable == null)
            throw new IllegalStateException("无法获取不存在的注册项类别，请先调用createCategory创建该类别");
        return (C) subtable;
    }
}
