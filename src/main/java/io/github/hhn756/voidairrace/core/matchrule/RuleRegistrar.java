package io.github.hhn756.voidairrace.core.matchrule;

import io.github.hhn756.voidairrace.constants.Categories;
import io.github.hhn756.voidairrace.core.matchrule.generalrules.BasicEndDetermination;
import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import io.github.hhn756.voidairrace.infrastructure.registry.Registry;

import java.util.Collection;
import java.util.List;

/**
 * 创建规则注册项类别，并注册插件内的所有内建比赛规则<br>
 * 用户包经脚本注册的规则由包加载链登记到同一类别
 * */
public class RuleRegistrar implements Module {
    @Override
    public Collection<Class<? extends Module>> getRequiredModules() {
        return List.of(Registry.class);
    }

    /** 插件启用时执行 */
    private void onLoad() {
        registerRules();
    }

    /** 插件禁用时执行 */
    private void onUnload() {}

    /**
     * 创建“比赛规则”注册项类别，然后注册插件内所有内建比赛规则
     * */
    private void registerRules() {
        Registry registry = Registry.getInstance();
        // 定义“比赛规则”类别，键计算：注册项所记录的规则 Id
        // （用带参数类型的 lambda 而非方法引用：RuleEntry 是泛型类，方法引用会让 createCategory 的两个重载产生歧义）
        registry.createCategory(Categories.RULE, (RuleEntry<?> entry) -> entry.getKey());
        registry.category(Categories.RULE).add(
                // 内建规则：附带子类的无参构造器作为工厂
                new RuleEntry<>(BasicEndDetermination.meta, BasicEndDetermination::new)
        );
    }
}
