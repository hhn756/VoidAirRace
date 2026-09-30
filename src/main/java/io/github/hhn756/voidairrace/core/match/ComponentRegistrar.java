package io.github.hhn756.voidairrace.core.match;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.constants.Categories;
import io.github.hhn756.voidairrace.core.match.componentbase.MatchComp;
import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import io.github.hhn756.voidairrace.infrastructure.registry.DefaultSubtable;
import io.github.hhn756.voidairrace.infrastructure.registry.Registry;
import io.github.hhn756.voidairrace.infrastructure.util.ClassScanner;

import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

/**
 * 自动注册插件内的所有比赛组件
 * */
public class ComponentRegistrar implements Module {
    @Override
    public Collection<Class<? extends Module>> getRequiredModules() {
        return List.of(Registry.class);
    }

    /**
     * 插件启用时执行<br>
     * 扫描注册等有副作用的工作必须在此完成：Modules 会先实例化全部模块，再按拓扑顺序加载
     * */
    private void onLoad() {
        registerComponents();
    }

    /** 插件停用时执行 */
    private void onUnload() {
    }

    /**
     * 添加“比赛组件”注册项类别，然后扫描并注册插件中所有比赛组件实现类
     */
    private void registerComponents() {
        Logger logger = VoidAirRace.getInstance().getLogger();
        Registry registry = Registry.getInstance();
        // 定义“比赛组件”类别，键计算：注册项所记录的组件类型本身
        registry.createCategory(Categories.COMPONENT, CompEntry::getCompType);
        DefaultSubtable<CompEntry, Class<MatchComp>> compSubtable =
                registry.category(Categories.COMPONENT);

        Collection<Class<MatchComp>> componentClasses = ClassScanner.scanSubclasses(MatchComp.class);
        for (Class<MatchComp> componentClass : componentClasses) {
            // 注册组件的无参构造器
            try {
                compSubtable.add(new CompEntry(componentClass));
            } catch (NoSuchMethodException e) {
                logger.warning("注册比赛组件 “"
                        + componentClass.getName()
                        + "” 失败，此组件类型没有公开的无参构造器！这可能是开发者的疏忽");
            }
        }
    }
}
