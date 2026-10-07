package io.github.hhn756.voidairrace.core.matchrule;

import io.github.hhn756.voidairrace.constants.Plugin;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.addons.GameElement;
import io.github.hhn756.voidairrace.core.addons.GameElementMeta;
import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.result.OperationResult;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.NonNull;

import java.util.List;

public interface MatchRule extends GameElement {
    /**
     * 规则类游戏元素的默认元数据
     * */
    GameElementMeta defaultMeta = new GameElementMeta(
            Plugin.key("default"),
            List.of(Component.translatable(
                    TranslateKeys.MatchComp.COMP_BASE_DEFAULT_NAME
            )),
            List.of(), List.of(), Component.empty(), 0L, List.of(), List.of()
    );

    @Override
    default @NonNull GameElementMeta getElementMeta() {
        return defaultMeta;
    };

    /**
     * 规则被启用时调用（例如比赛开始时或中途添加）
     *
     * @param match 当前比赛实例
     *
     * @return 如果返回失败的结果那么将会取消这次启用规则操作
     */
    default @NonNull OperationResult onEnable(@NonNull Match match) {
        return OperationResult.success();
    }

    /**
     * 规则被禁用时调用（例如比赛结束或中途移除）
     */
    default void onDisable(@NonNull Match match) {}

    /**
     * 规则加载时每游戏刻自动执行一次（注意：避免包含耗时操作）
     */
    default void tick(@NonNull Match match) {}
}
