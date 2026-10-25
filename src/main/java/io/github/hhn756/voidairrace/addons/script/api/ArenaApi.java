package io.github.hhn756.voidairrace.addons.script.api;

import io.github.hhn756.voidairrace.Voidairrace;
import io.github.hhn756.voidairrace.addons.script.api.handle.ArenaHandle;
import io.github.hhn756.voidairrace.addons.usrpackage.UsrPackage;
import io.github.hhn756.voidairrace.event.UsrPackageUnloadEvent;
import io.github.hhn756.voidairrace.result.ValueResult;
import io.github.hhn756.voidairrace.service.arena.ArenaManager;
import io.github.hhn756.voidairrace.service.arena.ArenaToken;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.LuaRuntimeException;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.Table;
import net.sandius.rembulan.impl.NonsuspendableFunctionException;
import net.sandius.rembulan.runtime.AbstractFunction0;
import net.sandius.rembulan.runtime.ExecutionContext;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 竞技场借用类别（全局表{@code arena}）的 Lua API 入口：{@code borrow}<br>
 * 本类完整拥有本类别全部逻辑：借据的发放、所属包借据账本的登记与随包卸载的
 * 清扫——每包一个实例，实例随包生死。竞技场句柄表（{@code ArenaHandle}）是
 * 跨类别共享的参数类型：本类别负责发放，比赛句柄（{@code widen}）负责消费
 * <p>
 * 清扫：监听{@link UsrPackageUnloadEvent}，包 Id 匹配即统一归还未归还的竞技场
 * （已登记为比赛区域的除外，归还职责在比赛结束流程）
 * */
public final class ArenaApi {
    /** 所属包 */
    private final @NonNull UsrPackage pkg;

    /** 共享 Lua 状态上下文（竞技场句柄表在此宇宙创建） */
    private final @NonNull StateContext stateContext;

    /** 包 Id → 已加载包对象的解析函数（竞技场模板跨包路径解析用） */
    private final @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver;

    /** 本包借出、尚未归还的竞技场句柄（包卸载清扫账本） */
    private final @NonNull List<@NonNull ArenaHandle> borrowedArenas = new ArrayList<>();

    private ArenaApi(
            @NonNull UsrPackage pkg,
            @NonNull StateContext stateContext,
            @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver
    ) {
        this.pkg = pkg;
        this.stateContext = stateContext;
        this.packageResolver = packageResolver;
        Bukkit.getPluginManager().registerEvents(new UnloadListener(), Voidairrace.getInstance());
    }

    /**
     * 向包脚本环境（{@code _G}）注入全局表{@code arena}：{@code borrow}，并创建
     * 持有本类别状态（借据账本）的包级实例
     *
     * @param stateContext    共享 Lua 状态上下文（建表与竞技场句柄表创建用）
     * @param pkg             环境所属的包
     * @param packageResolver 包 Id → 已加载包对象的解析函数，查无此包返回{@code null}
     * @param env             包脚本环境（{@code _G}），全局表装入目标
     * */
    public static void installInto(
            @NonNull StateContext stateContext,
            @NonNull UsrPackage pkg,
            @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver,
            @NonNull Table env
    ) {
        ArenaApi api = new ArenaApi(pkg, stateContext, packageResolver);
        Table arena = stateContext.newTable();
        arena.rawset(ByteString.of("borrow"), new ArenaBorrow(api));
        env.rawset(ByteString.of("arena"), arena);
    }

    // ------ 供本类别函数类与 ArenaHandle 访问的状态 ------

    /** 所属包 */
    public @NonNull UsrPackage pkg() {
        return pkg;
    }

    /** 共享 Lua 状态上下文 */
    public @NonNull StateContext stateContext() {
        return stateContext;
    }

    /** 包 Id → 已加载包对象的解析函数，查无此包返回{@code null} */
    public @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver() {
        return packageResolver;
    }

    /** 记录一个新借出的竞技场句柄（ArenaHandle 构造时自动调用） */
    public void trackArena(@NonNull ArenaHandle arena) {
        borrowedArenas.add(arena);
    }

    /** 从清扫账本移除已归还的竞技场句柄（显式归还时自动调用） */
    public void forgetArena(@NonNull ArenaHandle arena) {
        borrowedArenas.remove(arena);
    }

    // ------ 包卸载清扫 ------

    /** 包卸载清扫监听器：只清本包借走的竞技场 */
    private final class UnloadListener implements Listener {
        @EventHandler
        public void onUnload(@NonNull UsrPackageUnloadEvent event) {
            if (!event.getPkgId().equals(pkg.id())) return;
            for (ArenaHandle arena : new ArrayList<>(borrowedArenas)) {
                arena.forceReturn();
            }
            borrowedArenas.clear();
            HandlerList.unregisterAll(this);
        }
    }

    // ------ 注入的函数 ------

    /**
     * {@code arena.borrow()}：借用一个竞技场，返回竞技场句柄表<br>
     * 借用协议由脚本驱动（load → 使用 → release）；包卸载时未归还的竞技场由
     * 引擎统一清扫归还；无空闲竞技场即抛 Lua 错误（消息含各占用者）
     * */
    private static final class ArenaBorrow extends AbstractFunction0 {
        private final @NonNull ArenaApi api;

        ArenaBorrow(@NonNull ArenaApi api) {
            this.api = api;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            ValueResult<ArenaToken> result = ArenaManager.getInstance().borrow(api.pkg.id());
            if (!(result instanceof ValueResult.WithValue(var token))) {
                throw new LuaRuntimeException(
                        "借用竞技场失败：" + (result.techMessage() != null ? result.techMessage() : "无空闲竞技场"));
            }
            ArenaHandle arena = new ArenaHandle(api, token);
            context.getReturnBuffer().setTo(arena.table());
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }
}
