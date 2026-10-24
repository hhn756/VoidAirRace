package io.github.hhn756.voidairrace.addons.script.api.event;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.addons.script.ScriptCallGate;
import io.github.hhn756.voidairrace.addons.usrpackage.UsrPackage;
import io.github.hhn756.voidairrace.event.MatchOverEvent;
import io.github.hhn756.voidairrace.event.PlayerLeaveMatchEvent;
import io.github.hhn756.voidairrace.event.UsrPackageUnloadEvent;
import io.github.hhn756.voidairrace.infrastructure.util.lua.ApiArgs;
import io.github.hhn756.voidairrace.result.ValueResult;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.LuaRuntimeException;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.Table;
import net.sandius.rembulan.impl.NonsuspendableFunctionException;
import net.sandius.rembulan.load.ChunkClassLoader;
import net.sandius.rembulan.runtime.AbstractFunction1;
import net.sandius.rembulan.runtime.AbstractFunction2;
import net.sandius.rembulan.runtime.ExecutionContext;
import net.sandius.rembulan.runtime.LuaFunction;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 事件订阅类别（全局表{@code event}）的 Lua API 入口：{@code on} 与 {@code off}<br>
 * 事件词表见{@link ScriptEvent}。本类完整拥有本类别全部逻辑：订阅登记、
 * 引擎事件的桥接派发与随包卸载的清扫——每包一个实例，实例随包生死
 * <p>
 * 事件桥接：每实例自注册一个 Bukkit 监听器，收到引擎事件后向本包订阅者派发
 * Lua 回调（每次调用经{@link ScriptCallGate}独立预算）；回调失败响亮处理
 * （记日志+广播原因），不自动终止比赛——全局回调不承担比赛推进职责，
 * 且比赛结束事件中触发 stop 存在重入风险
 * <p>
 * 清扫：监听{@link UsrPackageUnloadEvent}，包 Id 匹配即注销桥接监听器、清空订阅
 * */
public final class EventApi {
    /** 所属包 */
    private final @NonNull UsrPackage pkg;

    /** 共享 Lua 状态上下文（事件回调经调用门在此宇宙执行） */
    private final @NonNull StateContext stateContext;

    /** 编译产物类加载器（事件回调经调用门生成 Lua 风格调用栈用） */
    private final @NonNull ChunkClassLoader chunkClassLoader;

    /** 事件订阅登记（登记顺序即派发顺序）；同一事件可登记多个监听器 */
    private final @NonNull List<@NonNull Subscription> subscriptions = new ArrayList<>();

    /** 事件桥接监听器（随实例注册，随清扫注销） */
    private final @NonNull BridgeListener bridgeListener;

    private EventApi(
            @NonNull UsrPackage pkg,
            @NonNull StateContext stateContext,
            @NonNull ChunkClassLoader chunkClassLoader
    ) {
        this.pkg = pkg;
        this.stateContext = stateContext;
        this.chunkClassLoader = chunkClassLoader;
        this.bridgeListener = new BridgeListener();
        Bukkit.getPluginManager().registerEvents(this.bridgeListener, VoidAirRace.getInstance());
        Bukkit.getPluginManager().registerEvents(new UnloadListener(), VoidAirRace.getInstance());
    }

    /**
     * 向包脚本环境（{@code _G}）注入全局表{@code event}：{@code on} / {@code off}，
     * 并创建持有本类别状态（订阅登记与桥接监听器）的包级实例
     *
     * @param stateContext     共享 Lua 状态上下文（建表与事件回调执行用）
     * @param chunkClassLoader 编译产物类加载器（事件回调经调用门生成 Lua 风格调用栈用）
     * @param pkg              环境所属的包
     * @param env              包脚本环境（{@code _G}），全局表装入目标
     * */
    public static void installInto(
            @NonNull StateContext stateContext,
            @NonNull ChunkClassLoader chunkClassLoader,
            @NonNull UsrPackage pkg,
            @NonNull Table env
    ) {
        EventApi api = new EventApi(pkg, stateContext, chunkClassLoader);
        Table event = stateContext.newTable();
        event.rawset(ByteString.of("on"), new On(api));
        event.rawset(ByteString.of("off"), new Off(api));
        env.rawset(ByteString.of("event"), event);
    }

    // ------ 供本类别函数类访问的状态 ------

    /**
     * 登记一个事件监听器<br>
     * 同一事件可登记多个监听器（含同一函数重复登记，按登记次数派发）
     *
     * @param event    订阅的事件类型（词表成员）
     * @param callback 事件触发时派发的 Lua 回调
     * */
    public void subscribe(@NonNull ScriptEvent event, @NonNull LuaFunction callback) {
        subscriptions.add(new Subscription(event, callback));
    }

    /**
     * 移除指定监听器函数在所有事件类型上的全部登记（函数按 Lua 函数值同一性比较，
     * 即必须是同一个函数值而非同源的另一个闭包）；未登记（或已随包清扫）时静默无操作
     * */
    public void unsubscribe(@NonNull LuaFunction callback) {
        subscriptions.removeIf(sub -> sub.callback() == callback);
    }

    /**
     * 向本包的某事件订阅者派发回调；每次调用独立预算，失败响亮处理（日志+广播），不中断其他订阅者
     * */
    private void dispatch(@NonNull ScriptEvent event, @NonNull Object... args) {
        for (Subscription sub : List.copyOf(subscriptions)) {
            if (sub.event() != event) continue;
            ValueResult<Object[]> result = ScriptCallGate.call(
                    stateContext, chunkClassLoader, sub.callback(), args);
            if (!result.hasValue()) {
                VoidAirRace.getInstance().getLogger().warning(
                        "包 " + pkg.id() + " 的事件回调出错（事件 " + event.eventName() + "）："
                                + result.techMessage());
                Bukkit.getServer().broadcast(Component.text(
                        "包 " + pkg.id() + " 的事件回调出错（事件 " + event.eventName() + "）",
                        NamedTextColor.RED));
            }
        }
    }

    /** 订阅条目：事件类型与回调 */
    private record Subscription(@NonNull ScriptEvent event, @NonNull LuaFunction callback) {}

    // ------ 引擎事件桥接 ------

    /** 引擎事件 → 本包 Lua 订阅者的桥接监听器 */
    private final class BridgeListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onPlayerLeaveMatch(@NonNull PlayerLeaveMatchEvent event) {
            dispatch(ScriptEvent.PLAYER_LEAVE_MATCH, ByteString.of(event.getPlayer().getUniqueId().toString()));
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onMatchOver(@NonNull MatchOverEvent event) {
            dispatch(ScriptEvent.MATCH_OVER);
        }
    }

    /** 包卸载清扫监听器：只清本包的订阅与桥接 */
    private final class UnloadListener implements Listener {
        @EventHandler
        public void onUnload(@NonNull UsrPackageUnloadEvent event) {
            if (!event.getPkgId().equals(pkg.id())) return;
            HandlerList.unregisterAll(bridgeListener);
            HandlerList.unregisterAll(this);
            subscriptions.clear();
        }
    }

    // ------ 注入的函数 ------

    /**
     * {@code event.on(name, callback)}：订阅引擎事件<br>
     * {@code name} 为词表事件名（见{@link ScriptEvent}），未知事件名即抛错；
     * 同一事件可登记多个监听器，派发按登记顺序，无返回值。
     * 回调在主线程执行，载荷为位置参数
     * */
    private static final class On extends AbstractFunction2 {
        private final @NonNull EventApi api;

        On(@NonNull EventApi api) {
            this.api = api;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1, @Nullable Object arg2) {
            String eventName = ApiArgs.expectString(arg1, "event.on：第 1 个参数（事件名）");
            if (!(arg2 instanceof LuaFunction callback)) {
                throw new LuaRuntimeException("event.on：第 2 个参数（回调）必须是函数");
            }
            api.subscribe(ScriptEvent.require(eventName), callback);
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code event.off(callback)}：按监听器函数退订<br>
     * 移除该函数在所有事件类型上的全部登记；函数未登记或已随包卸载时静默无操作
     * */
    private static final class Off extends AbstractFunction1 {
        private final @NonNull EventApi api;

        Off(@NonNull EventApi api) {
            this.api = api;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            if (!(arg1 instanceof LuaFunction callback)) {
                throw new LuaRuntimeException("event.off：第 1 个参数（监听器函数）必须是函数");
            }
            api.unsubscribe(callback);
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }
}
