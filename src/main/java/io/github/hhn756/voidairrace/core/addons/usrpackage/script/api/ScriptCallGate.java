package io.github.hhn756.voidairrace.core.addons.usrpackage.script.api;

import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.result.ValueResult;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.exec.CallException;
import net.sandius.rembulan.exec.CallPausedException;
import net.sandius.rembulan.exec.DirectCallExecutor;
import net.sandius.rembulan.load.ChunkClassLoader;
import org.bukkit.Bukkit;
import org.jspecify.annotations.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Java 侧调用 Lua 的唯一入口，集中处理调用的横切关注点<br>
 * 所有由插件主动发起的 Lua 调用（包入口脚本执行、未来的注册工厂与回调调用）都必须经过本类：
 * <ul>
 *     <li>主线程断言：Lua 调用只在主线程执行</li>
 *     <li>指令预算：单次调用有 tick 上限，防止用户脚本死循环卡死服务器</li>
 *     <li>异常映射：把 rembulan 的调用异常翻译为结果类型的失败通道</li>
 * </ul>
 * 本类是无状态的静态工具类，不可实例化；调用全部发生在主线程
 * */
public final class ScriptCallGate {
    /**
     * 单次调用的默认指令预算（tick 数）<br>
     * rembulan 的 tick 由编译代码按基本块申请，粗略对应“一小段不掺调用的直线代码”
     * */
    public static final long DEFAULT_TICK_LIMIT = 5_000_000L;

    /** 静态工具类，不可实例化 */
    private ScriptCallGate() {}

    /**
     * 调用一个 Lua 函数并等待其执行完成
     *
     * @param stateContext     Lua 状态上下文
     * @param chunkClassLoader 与编译用户 chunk 所用一致的类加载器，用于生成 Lua 风格调用栈文本
     * @param fn               要调用的 Lua 函数（或任何可调用值）
     * @param args             传给函数的参数
     *
     * @return 成功时携带函数的返回值数组
     * */
    public static @NonNull ValueResult<Object[]> call(
            @NonNull StateContext stateContext,
            @NonNull ChunkClassLoader chunkClassLoader,
            @NonNull Object fn,
            @NonNull Object... args
    ) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Lua 调用只能在服务器主线程执行");

        // 每次调用使用全新 executor：预算按调用隔离，避免上一次调用的暂停状态影响本次
        DirectCallExecutor executor = DirectCallExecutor.newExecutorWithTickLimit(DEFAULT_TICK_LIMIT);
        try {
            return ValueResult.success(executor.call(stateContext, fn, args));
        } catch (CallPausedException e) {
            // 两种来源：预算耗尽被调度器暂停；主协程顶层让出（沙箱不支持让出恢复）
            return ValueResult.failure(
                    TranslateKeys.Addons.USR_PACKAGE_ENTRY_SCRIPT_FAILED,
                    null,
                    "脚本执行中止：指令预算（" + DEFAULT_TICK_LIMIT + " ticks）耗尽，或在顶层让出",
                    e
            );
        } catch (CallException e) {
            return ValueResult.failure(
                    TranslateKeys.Addons.USR_PACKAGE_ENTRY_SCRIPT_FAILED,
                    null,
                    "脚本执行出错：" + describe(e, chunkClassLoader),
                    e.getCause()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ValueResult.failure(
                    TranslateKeys.Addons.USR_PACKAGE_ENTRY_SCRIPT_FAILED,
                    null,
                    "脚本执行被中断",
                    e
            );
        }
    }

    /**
     * 生成一次调用失败的描述文本：优先携带 Lua 风格的调用栈（含源码位置），便于包作者定位问题
     * */
    private static @NonNull String describe(@NonNull CallException e, @NonNull ChunkClassLoader chunkClassLoader) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        e.printLuaFormatStackTraceback(
                new PrintStream(buffer, true, StandardCharsets.UTF_8),
                chunkClassLoader,
                null
        );
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
