package io.github.hhn756.voidairrace.addons.usrpackage.script.api.register;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.addons.GameElementMeta;
import io.github.hhn756.voidairrace.addons.usrpackage.UsrPackage;
import io.github.hhn756.voidairrace.addons.usrpackage.script.api.LuaHandles;
import io.github.hhn756.voidairrace.addons.usrpackage.script.api.ScriptCallGate;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.core.matchrule.MatchRule;
import io.github.hhn756.voidairrace.core.matchrule.RuleEntry;
import io.github.hhn756.voidairrace.result.OperationResult;
import io.github.hhn756.voidairrace.result.ValueResult;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.load.ChunkClassLoader;
import net.sandius.rembulan.runtime.LuaFunction;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

/**
 * 用户规则的 Java 适配器：把脚本注册的规则描述适配为{@link MatchRule}<br>
 * 每次经注册项实例化规则时产出一个全新的本类实例；脚本提供的
 * {@code on_enable}/{@code on_disable}/{@code tick} 回调在对应生命周期经调用门触发，
 * 参数为当前比赛的句柄表（见{@link LuaHandles}）
 * <p>
 * 回调失败语义：{@code on_enable} 失败（调用失败或返回协议不通过）取消启用规则；
 * {@code on_disable}/{@code tick} 失败只记日志，不中断比赛收尾
 * <p>
 * 本类型是所有用户规则的统一类型：不同规则的差异完全由构造参数（元数据、回调）表达，
 * 不存在也不需要子类
 * <p>
 * 实例化约束：只能经{@link #entry}创建注册项（构造器包私有），规则实例只能由注册项实例化，
 * 其余代码不应也无法实例化本类
 * <p>
 * 归属元素注册类别：仅规则注册构造本类，与{@code RegisterApi}同包封装
 * */
public final class LuaRule implements MatchRule {
    private final @NonNull GameElementMeta meta;
    private final @NonNull UsrPackage source;
    /** 共享 Lua 状态上下文 */
    private final @NonNull StateContext stateContext;
    /** 编译产物类加载器，供调用门生成 Lua 风格调用栈 */
    private final @NonNull ChunkClassLoader chunkClassLoader;
    /** 脚本提供的 on_enable 函数，未提供为{@code null} */
    private final @Nullable LuaFunction onEnableFn;
    /** 脚本提供的 on_disable 函数，未提供为{@code null} */
    private final @Nullable LuaFunction onDisableFn;
    /** 脚本提供的 tick 函数，未提供为{@code null} */
    private final @Nullable LuaFunction tickFn;
    /** 日志与错误消息中的归属描述（包Id:规则键名） */
    private final @NonNull String owner;

    /**
     * 构造一个用户规则实例<br>
     * 仅限本规则对应的注册项调用
     *
     * @param meta             规则的元数据（含标签），来自注册时提供的信息
     * @param source           注册此规则的来源用户包
     * @param stateContext     共享 Lua 状态上下文
     * @param chunkClassLoader 编译产物类加载器
     * @param onEnableFn       脚本提供的 on_enable 函数，未提供为{@code null}
     * @param onDisableFn      脚本提供的 on_disable 函数，未提供为{@code null}
     * @param tickFn           脚本提供的 tick 函数，未提供为{@code null}
     * @param owner            归属描述（包Id:规则键名），用于日志与错误消息
     * */
    LuaRule(
            @NonNull GameElementMeta meta,
            @NonNull UsrPackage source,
            @NonNull StateContext stateContext,
            @NonNull ChunkClassLoader chunkClassLoader,
            @Nullable LuaFunction onEnableFn,
            @Nullable LuaFunction onDisableFn,
            @Nullable LuaFunction tickFn,
            @NonNull String owner
    ) {
        this.meta = meta;
        this.source = source;
        this.stateContext = stateContext;
        this.chunkClassLoader = chunkClassLoader;
        this.onEnableFn = onEnableFn;
        this.onDisableFn = onDisableFn;
        this.tickFn = tickFn;
        this.owner = owner;
    }

    /**
     * 构造一个用户规则注册项<br>
     * 本方法是全项目唯一调用{@link LuaRule}构造器的位置：注册项的工厂在每次
     * {@link RuleEntry#newInstance()} 时构造一个全新的 {@link LuaRule} 实例，
     * 规则实例化因此只能经注册项发生
     *
     * @param meta             规则的元数据（含标签），来自注册时提供的信息
     * @param source           注册此规则的来源用户包
     * @param callbacks        回调工厂，每个规则实例构造时调用一次以获得该实例专属的生命周期函数
     * @return 新的用户规则注册项
     * */
    public static @NonNull RuleEntry<LuaRule> entry(
            @NonNull GameElementMeta meta,
            @NonNull UsrPackage source,
            @NonNull Supplier<@NonNull Callbacks> callbacks
    ) {
        return new RuleEntry<>(meta, () -> {
            Callbacks cbs = callbacks.get();
            return new LuaRule(
                    meta, source,
                    cbs.stateContext(),
                    cbs.chunkClassLoader(),
                    cbs.onEnableFn(),
                    cbs.onDisableFn(),
                    cbs.tickFn(),
                    cbs.owner()
            );
        });
    }

    /**
     * @return 注册此规则的来源用户包
     * */
    public @NonNull UsrPackage source() {
        return source;
    }

    @Override
    public @NonNull GameElementMeta getElementMeta() {
        return meta;
    }

    @Override
    public @NonNull OperationResult onEnable(@NonNull Match match) {
        if (onEnableFn == null) return OperationResult.success();
        ValueResult<Object[]> result = ScriptCallGate.call(
                stateContext, chunkClassLoader, onEnableFn, LuaHandles.forMatch(stateContext, match));
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
        callVoid(match, onDisableFn, "on_disable");
    }

    @Override
    public void tick(@NonNull Match match) {
        callVoid(match, tickFn, "tick");
    }

    /**
     * 调用一个无返回协议的生命周期函数：失败只记日志不上抛（void 生命周期无处映射结果）
     * */
    private void callVoid(@NonNull Match match, @Nullable LuaFunction fn, @NonNull String what) {
        if (fn == null) return;
        ValueResult<Object[]> result = ScriptCallGate.call(
                stateContext, chunkClassLoader, fn, LuaHandles.forMatch(stateContext, match));
        if (!result.hasValue()) {
            VoidAirRace.getInstance().getLogger().warning(
                    "用户规则的 " + what + " 回调调用失败：" + owner + "（" + result.techMessage() + "）"
            );
        }
    }

    /**
     * 用户规则的生命周期函数集合（构造注册项时传入的回调工厂的产物）<br>
     * 各函数允许为{@code null}（脚本未提供时对应生命周期采用默认行为）
     *
     * @param stateContext     共享 Lua 状态上下文
     * @param chunkClassLoader 编译产物类加载器
     * @param onEnableFn       脚本提供的 on_enable 函数，未提供为{@code null}
     * @param onDisableFn      脚本提供的 on_disable 函数，未提供为{@code null}
     * @param tickFn           脚本提供的 tick 函数，未提供为{@code null}
     * @param owner            归属描述（包Id:规则键名），用于日志与错误消息
     * */
    public record Callbacks(
            @NonNull StateContext stateContext,
            @NonNull ChunkClassLoader chunkClassLoader,
            @Nullable LuaFunction onEnableFn,
            @Nullable LuaFunction onDisableFn,
            @Nullable LuaFunction tickFn,
            @NonNull String owner
    ) {}
}
