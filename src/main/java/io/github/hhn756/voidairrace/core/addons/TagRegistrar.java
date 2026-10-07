package io.github.hhn756.voidairrace.core.addons;

import io.github.hhn756.voidairrace.constants.Categories;
import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import io.github.hhn756.voidairrace.infrastructure.registry.Registry;

import java.util.Collection;
import java.util.List;

/**
 * 创建标签注册项类别<br>
 * 不注册任何内建标签：标签词汇表完全由用户包按需注册，插件侧将来出现标准词汇需求时再补充
 * */
public class TagRegistrar implements Module {
    @Override
    public Collection<Class<? extends Module>> getRequiredModules() {
        return List.of(Registry.class);
    }

    /** 插件启用时执行 */
    private void onLoad() {
        Registry registry = Registry.getInstance();
        // 定义“游戏元素标签”类别，键计算：注册项所记录的标签 Id
        registry.createCategory(Categories.TAG, TagEntry::getKey);
    }

    /** 插件禁用时执行 */
    private void onUnload() {}
}
