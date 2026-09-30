package io.github.hhn756.voidairrace.constants;

import io.github.hhn756.voidairrace.core.map.MapEntry;
import io.github.hhn756.voidairrace.core.match.CompEntry;
import io.github.hhn756.voidairrace.core.match.componentbase.MatchComp;
import io.github.hhn756.voidairrace.core.matchrule.RuleEntry;
import io.github.hhn756.voidairrace.core.playerstatemanager.StateSystemEntry;
import io.github.hhn756.voidairrace.infrastructure.registry.CategoryId;
import io.github.hhn756.voidairrace.infrastructure.registry.DefaultSubtable;
import org.bukkit.NamespacedKey;
import org.jspecify.annotations.NonNull;

/**
 * 插件内所有注册项类别的标识<br>
 * 调用{@link io.github.hhn756.voidairrace.infrastructure.registry.Registry#category(CategoryId)}
 * 获取子表时以这些常量指定目标类别
 * <p>
 * 新增注册项类别时应在本类补充对应常量，并声明其注册项类型、键类型和子表实现类型<br>
 * （使用默认子表时子表类型参数写{@link DefaultSubtable}）
 * */
public class Categories {
    /** 游戏地图 */
    public static final @NonNull CategoryId<MapEntry, NamespacedKey, DefaultSubtable<MapEntry, NamespacedKey>>
            MAP = new CategoryId<>();

    /** 比赛组件 */
    public static final @NonNull CategoryId<CompEntry, Class<MatchComp>, DefaultSubtable<CompEntry, Class<MatchComp>>>
            COMPONENT = new CategoryId<>();

    /** 比赛规则 */
    public static final @NonNull CategoryId<RuleEntry<?>, NamespacedKey, DefaultSubtable<RuleEntry<?>, NamespacedKey>>
            RULE = new CategoryId<>();

    /** 玩家状态体系 */
    public static final @NonNull CategoryId<StateSystemEntry, String, DefaultSubtable<StateSystemEntry, String>>
            STATESYSTEM = new CategoryId<>();
}
