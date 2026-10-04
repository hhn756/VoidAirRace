package io.github.hhn756.voidairrace.core.addons.usrpackage.script;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.addons.UserRule;
import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.result.OperationResult;
import io.github.hhn756.voidairrace.result.ValueResult;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.Table;
import net.sandius.rembulan.runtime.LuaFunction;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 用户规则的 Lua 行为适配器：把脚本提供的规则生命周期函数适配为{@link UserRule.Callback}<br>
 * 每个用户规则实例对应一个本类实例（经{@link UserRule#entry}的回调工厂构造），
 * 并持有该实例专属的 Match 空句柄表（真实 Match 句柄将在后续版本引入，届时填充同一张表）
 * <p>
 * 所有 Lua 调用经{@link ScriptCallGate}：主线程断言、指令预算与异常映射由其统一处理
 * */
final class LuaRuleCallback implements UserRule.Callback {
    private final @NonNull ScriptCallGate gate;
    private final @NonNull StateContext stateContext;
    private final @Nullable LuaFunction onEnableFn;
    private final @Nullable LuaFunction onDisableFn;
    private final @Nullable LuaFunction tickFn;
    /** 日志与错误消息中的归属描述（包Id:规则键名） */
    private final @NonNull String owner;
    /** 此实例专属的 Match 空句柄表，每次回调都作为第一个参数传入 */
    private final @NonNull Table matchHandle;

    /**
     * 构造一个用户规则的行为适配器
     *
     * @param gate          Java 调 Lua 的唯一门
     * @param stateContext  共享 Lua 状态上下文
     * @param onEnableFn    脚本提供的 on_enable 函数，未提供为{@code null}
     * @param onDisableFn   脚本提供的 on_disable 函数，未提供为{@code null}
     * @param tickFn        脚本提供的 tick 函数，未提供为{@code null}
     * @param owner         归属描述（包Id:规则键名），用于日志与错误消息
     * */
    LuaRuleCallback(
            @NonNull ScriptCallGate gate,
            @NonNull StateContext stateContext,
            @Nullable LuaFunction onEnableFn,
            @Nullable LuaFunction onDisableFn,
            @Nullable LuaFunction tickFn,
            @NonNull String owner
    ) {
        this.gate = gate;
        this.stateContext = stateContext;
        this.onEnableFn = onEnableFn;
        this.onDisableFn = onDisableFn;
        this.tickFn = tickFn;
        this.owner = owner;
        this.matchHandle = stateContext.newTable();
    }

    @Override
    public @NonNull OperationResult onEnable(@NonNull Match match) {
        if (onEnableFn == null) return OperationResult.success();
        ValueResult<Object[]> result = gate.call(stateContext, onEnableFn, matchHandle);
        if (!result.hasValue()) {
            return OperationResult.failure(
                    TranslateKeys.MatchRule.USER_RULE_ON_ENABLE_FAILED,
                    "用户规则 on_enable 调用失败：" + owner + "（" + result.techMessage() + "）"
            );
        }
        Object[] returns = result.value();
        // 返回协议：ok[, err] —— ok 为布尔值且为 true 才算成功；
        // false 或格式不对（nil/其他类型/无返回）一律失败，err 为字符串时作为失败原因
        if (returns.length >= 1 && returns[0] instanceof Boolean ok) {
            if (ok) return OperationResult.success();
            String err = returns.length >= 2 && returns[1] instanceof ByteString bs ? bs.decode() : null;
            return OperationResult.failure(
                    TranslateKeys.MatchRule.USER_RULE_ON_ENABLE_FAILED,
                    "用户规则 on_enable 返回失败：" + owner + (err == null ? "" : "：" + err)
            );
        }
        return OperationResult.failure(
                TranslateKeys.MatchRule.USER_RULE_ON_ENABLE_FAILED,
                "用户规则 on_enable 返回格式不对（预期 ok[, err]，ok 必须为布尔值）：" + owner
        );
    }

    @Override
    public void onDisable(@NonNull Match match) {
        callVoid(onDisableFn, "on_disable");
    }

    @Override
    public void tick(@NonNull Match match) {
        callVoid(tickFn, "tick");
    }

    /**
     * 调用一个无返回协议的生命周期函数：失败只记日志不上抛（void 生命周期无处映射结果）
     * */
    private void callVoid(@Nullable LuaFunction fn, @NonNull String what) {
        if (fn == null) return;
        ValueResult<Object[]> result = gate.call(stateContext, fn, matchHandle);
        if (!result.hasValue()) {
            VoidAirRace.getInstance().getLogger().warning(
                    "用户规则 " + what + " 调用失败：" + owner + "（" + result.techMessage() + "）"
            );
        }
    }
}
