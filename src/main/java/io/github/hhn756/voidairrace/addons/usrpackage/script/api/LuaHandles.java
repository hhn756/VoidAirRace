package io.github.hhn756.voidairrace.addons.usrpackage.script.api;

import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.core.match.Match.HandleContext;
import io.github.hhn756.voidairrace.infrastructure.util.lua.ApiArgs;
import net.kyori.adventure.text.Component;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.LuaRuntimeException;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.Table;
import net.sandius.rembulan.impl.NonsuspendableFunctionException;
import net.sandius.rembulan.runtime.AbstractFunction0;
import net.sandius.rembulan.runtime.AbstractFunction1;
import net.sandius.rembulan.runtime.ExecutionContext;
import org.bukkit.Bukkit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.WeakHashMap;

/**
 * 比赛句柄表构建器：把一场比赛包装为脚本可用的普通 Lua 值（表）<br>
 * 表内键为函数名，值为闭包住该比赛{@link Match.HandleContext}的 Java 函数——
 * 绑定方向只有 Lua 值 → Java 比赛，任何拿到表的脚本都能调用（Lua 值语义，
 * 不检查持有者）；比赛结束后句柄函数调用即抛 Lua 错误
 * <p>
 * 同一场比赛的所有回调（规则生命周期、地图开始/结束）经 {@link #forMatch}
 * 拿到同一张表；本类是跨类别的共用层（与{@link ScriptCallGate}同类），不绑定单一注册类别
 * */
public final class LuaHandles {
    /**
     * 每场比赛的句柄表缓存（弱键：比赛实例被回收后条目随之消失，
     * 已分离的句柄函数不驻留任何比赛数据；所有回调均在主线程，无并发问题）
     * */
    private static final @NonNull WeakHashMap<@NonNull Match, @NonNull Table> CACHE = new WeakHashMap<>();

    /** 静态工具类，不可实例化 */
    private LuaHandles() {}

    /**
     * 获取指定比赛的句柄表（懒构建，每场比赛一张）
     *
     * @param stateContext 共享 Lua 状态上下文（构建表时用）
     * @param match        目标比赛
     *
     * @return 该比赛的句柄表
     * */
    public static @NonNull Table forMatch(@NonNull StateContext stateContext, @NonNull Match match) {
        Table table = CACHE.get(match);
        if (table == null) {
            table = build(stateContext, match.handleContext());
            CACHE.put(match, table);
        }
        return table;
    }

    /**
     * 构建一张句柄表：全部函数闭包住同一句柄上下文
     * */
    private static @NonNull Table build(@NonNull StateContext stateContext, @NonNull HandleContext ctx) {
        Table table = stateContext.newTable();
        table.rawset(ByteString.of("broadcast"), new Broadcast(ctx));
        table.rawset(ByteString.of("state"), new State(ctx));
        return table;
    }

    /**
     * 取回句柄所属的比赛，已分离即抛 Lua 错误<br>
     * 所有句柄函数的前置检查：既是「比赛是否仍可用」的判定点，
     * 也是比赛结束后使用句柄的唯一报错出口
     * */
    private static @NonNull Match requireMatch(@NonNull HandleContext ctx) {
        Match match = ctx.get();
        if (match == null) throw new LuaRuntimeException("比赛已结束，比赛句柄不可用");
        return match;
    }

    // ------ 句柄函数 ------

    /**
     * {@code handle.broadcast(message)}：向全服广播一条文本消息<br>
     * 参数必须是字符串；纯文本发送，富文本组件 API 属于后续版本
     * */
    private static final class Broadcast extends AbstractFunction1 {
        private final @NonNull HandleContext ctx;

        Broadcast(@NonNull HandleContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            requireMatch(ctx);
            String message = ApiArgs.expectString(arg1, "handle.broadcast：第 1 个参数（消息）");
            Bukkit.broadcast(Component.text(message));
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code handle.state()}：获取比赛当前状态名<br>
     * 返回状态名字符串（如 {@code "IN_PROGRESS"}），与比赛状态枚举一一对应
     * */
    private static final class State extends AbstractFunction0 {
        private final @NonNull HandleContext ctx;

        State(@NonNull HandleContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            Match match = requireMatch(ctx);
            context.getReturnBuffer().setTo(ByteString.of(match.state().name()));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }
}
