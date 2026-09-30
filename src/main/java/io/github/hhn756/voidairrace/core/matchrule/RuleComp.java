package io.github.hhn756.voidairrace.core.matchrule;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.constants.Categories;
import io.github.hhn756.voidairrace.constants.Plugin;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.addons.GameElementMeta;
import io.github.hhn756.voidairrace.core.match.ComponentPriority;
import io.github.hhn756.voidairrace.core.match.DataKey;
import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.core.match.componentbase.CustomData;
import io.github.hhn756.voidairrace.core.match.componentbase.EndableComp;
import io.github.hhn756.voidairrace.core.match.componentbase.MatchComp;
import io.github.hhn756.voidairrace.core.match.componentbase.StartableComp;
import io.github.hhn756.voidairrace.infrastructure.registry.Registry;
import io.github.hhn756.voidairrace.result.OperationResult;
import io.github.hhn756.voidairrace.result.ValueResult;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.NonNull;

import java.util.*;

public class RuleComp extends MatchComp
        implements StartableComp<CustomData, CustomData>,
        EndableComp<CustomData, CustomData> {

    private static final GameElementMeta meta = GameElementMeta.onlyId(Plugin.key("rule_comp"));

    private Match match;
    private BukkitTask tickTask;
    private final Set<MatchRule> activeRules = new HashSet<>();
    private final Map<MatchRule, Listener> ruleListeners = new HashMap<>();

    @Override
    public @NonNull GameElementMeta getMeta() {
        return meta;
    }

    @Override
    public @NonNull DataKey<?> getSCK() {
        return DataKey.of(RuleComp.class, CustomData.class);
    }

    @Override
    public int getInstallPriority() {
        return ComponentPriority.EXTREMELY_LOW.getValue();
    }

    @Override
    public @NonNull ValueResult<CustomData> install(@NonNull Match match, CustomData startArg) {
        this.match = match;
        // 启动 tick 调度器
        tickTask = Bukkit.getScheduler().runTaskTimer(VoidAirRace.getInstance(), () -> {
            new ArrayList<>(activeRules).forEach(rule -> rule.tick(match));
        }, 0L, 1L);
        return ValueResult.empty();
    }

    @Override
    public @NonNull DataKey<?> getECK() {
        return DataKey.of(RuleComp.class, CustomData.class);
    }

    @Override
    public int getUninstallPriority() {
        return ComponentPriority.HIGH.getValue();
    }

    @Override
    public @NonNull ValueResult<CustomData> uninstall(@NonNull Match match, CustomData endArg) {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        disableAllRules();
        return ValueResult.empty();
    }

    /**
     * 启用指定id的规则<br>
     * 如果规则是 Bukkit 事件监听器，那么会自动 注册 它
     *
     * @param ruleId 指定规则id
     *
     * @return 启用结果
     */
    public @NonNull OperationResult enableRule(@NonNull NamespacedKey ruleId) {
        RuleEntry<?> getResult = Registry.getInstance().category(Categories.RULE).get(ruleId);
        if (getResult == null) {
            // 规则 id 属于服务端参数，只进技术性消息
            return OperationResult.failure(
                    TranslateKeys.MatchComp.RULE_COMP_ENABLE_RULE_FAILURE_NOT_FOUND_ID,
                    "规则未注册：" + ruleId
            );
        }
        return enableRule(getResult.newInstance());
    }

    /**
     * 启用一个已实例化的规则<br>
     * 如果规则是 Bukkit 事件监听器，那么会自动 注册 它
     *
     * @param rule 指定规则
     */
    private @NonNull OperationResult enableRule(@NonNull MatchRule rule) {
        if (activeRules.contains(rule)) {
            return OperationResult.failure(TranslateKeys.MatchComp.RULE_COMP_ALREADY_ENABLED);
        }
        OperationResult enableResult = rule.onEnable(match);
        if (!enableResult.isSuccess()) {
            // 规则启用失败：失败信息已是本层形式，原样向上传播
            return enableResult;
        }
        activeRules.add(rule);
        if (rule instanceof Listener listener) {
            Bukkit.getPluginManager().registerEvents(listener, VoidAirRace.getInstance());
            ruleListeners.put(rule, listener);
        }
        return OperationResult.success();
    }

    /**
     * 禁用所有规则
     */
    public void disableAllRules() {
        new ArrayList<>(activeRules).forEach(this::disableRule);
    }

    /**
     * 禁用指定规则实例<br>
     * 如果规则是 Bukkit 事件监听器，那么会自动 移除 它
     *
     * @param rule 指定规则实例
     */
    private void disableRule(@NonNull MatchRule rule) {
        if (!activeRules.contains(rule)) return;
        rule.onDisable(match);
        if (ruleListeners.containsKey(rule)) {
            HandlerList.unregisterAll(ruleListeners.get(rule));
            ruleListeners.remove(rule);
        }
        activeRules.remove(rule);
    }

    /**
     * 获取当前启用的规则列表
     */
    public Set<MatchRule> getActiveRules() {
        return Collections.unmodifiableSet(activeRules);
    }

//    @Override
//    public void contributeToRecord(MatchRecord record) {
//        List<String> activeRuleIds = activeRules.stream()
//                .map(MatchRule::getId)
//                .toList();
//        record.getComponentData().put("ruleComponent.activeRuleIds", activeRuleIds);
//    }
}
