package io.github.hhn756.voidairrace.addons.usrpackage.script.api.handle;

import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.event.MatchOverEvent;
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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.WeakHashMap;

/**
 * 比赛句柄 API：把一场比赛包装为脚本可用的普通 Lua 值（句柄表），并全权管理句柄生命周期<br>
 * 句柄表内键为函数名，值为闭包住该比赛{@link HandleContext}的 Java 函数——
 * 绑定方向只有 Lua 值 → Java 比赛，任何拿到表的脚本都能调用（Lua 值语义，
 * 不检查持有者）；比赛结束后句柄函数调用即抛 Lua 错误
 * <p>
 * 同一场比赛的所有回调（规则生命周期、地图开始/结束）经{@link #forMatch}拿到同一张表；
 * 生命周期完全由本类管理：句柄上下文懒创建于首次取表，
 * 由{@link MatchOverListener}在比赛结束时分离（比赛本体不参与句柄管理）
 * <p>
 * 本类不向包环境注入任何全局函数（句柄表只能经回调首参获得），
 * 「API 类别入口」的形态与其他类别不同：对外契约是{@link #forMatch}与{@link MatchOverListener}
 * */
public final class HandleApi {
    /**
     * 每场比赛的句柄条目缓存（弱键：比赛实例被回收后条目随之消失；
     * 所有回调均在主线程，无并发问题）
     * */
    private static final @NonNull WeakHashMap<@NonNull Match, @NonNull Entry> CACHE = new WeakHashMap<>();

    /** 静态工具类，不可实例化 */
    private HandleApi() {}

    /**
     * 获取指定比赛的句柄表（懒构建，每场比赛一张）
     *
     * @param stateContext 共享 Lua 状态上下文（构建表时用）
     * @param match        目标比赛
     *
     * @return 该比赛的句柄表
     * */
    public static @NonNull Table forMatch(@NonNull StateContext stateContext, @NonNull Match match) {
        Entry entry = CACHE.get(match);
        if (entry == null) {
            entry = new Entry(stateContext, match);
            CACHE.put(match, entry);
        }
        return entry.table;
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

    // ------ 句柄上下文与缓存条目 ------

    /**
     * 比赛句柄的共享上下文<br>
     * 全部句柄函数捕获本实例而非比赛本身：比赛结束后{@link #detach()}置空内部引用，
     * 一次操作即断开脚本侧全部句柄函数对本比赛对象图的强引用（防 GC 驻留）
     * */
    static final class HandleContext {
        /** 所属比赛；分离后为{@code null} */
        private @Nullable Match match;

        HandleContext(@NonNull Match match) {
            this.match = match;
        }

        /**
         * 获取句柄所属的比赛
         *
         * @return 所属比赛实例；比赛已结束（已分离）时为{@code null}
         * */
        @Nullable Match get() {
            return match;
        }

        /** 分离句柄上下文：置空内部比赛引用，之后的{@link #get()}恒返回{@code null} */
        void detach() {
            match = null;
        }
    }

    /**
     * 缓存条目：一场比赛的句柄上下文与其句柄表<br>
     * 条目在比赛分离后仍然保留（弱键负责最终回收）：
     * 后续对同一比赛取表恒返回同一张已失效的表，而不是重建出可复活的新表
     * */
    private static final class Entry {
        private final @NonNull HandleContext ctx;
        private final @NonNull Table table;

        Entry(@NonNull StateContext stateContext, @NonNull Match match) {
            this.ctx = new HandleContext(match);
            this.table = stateContext.newTable();
            this.table.rawset(ByteString.of("broadcast"), new Broadcast(this.ctx));
            this.table.rawset(ByteString.of("state"), new State(this.ctx));
        }
    }

    /**
     * 比赛结束监听器：分离结束比赛的句柄上下文<br>
     * 句柄生命周期的外部管理点——比赛本体不感知句柄，
     * 断链时机由本监听器保证（注册于脚本运行时模块，随其加载/卸载）
     * */
    public static final class MatchOverListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onMatchOver(@NonNull MatchOverEvent event) {
            Entry entry = CACHE.get(event.getMatch());
            if (entry != null) entry.ctx.detach();
        }
    }
}
