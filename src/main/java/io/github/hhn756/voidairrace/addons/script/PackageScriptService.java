package io.github.hhn756.voidairrace.addons.script;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.addons.script.api.PackageRequire;
import io.github.hhn756.voidairrace.addons.script.api.PluginApi;
import io.github.hhn756.voidairrace.addons.usrpackage.UsrPackage;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.event.UsrPackageUnloadEvent;
import io.github.hhn756.voidairrace.exception.UsrPackageException;
import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import io.github.hhn756.voidairrace.result.ValueResult;
import net.kyori.adventure.text.Component;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.Table;
import net.sandius.rembulan.Variable;
import net.sandius.rembulan.compiler.CompilerChunkLoader;
import net.sandius.rembulan.env.RuntimeEnvironment;
import net.sandius.rembulan.env.RuntimeEnvironments;
import net.sandius.rembulan.impl.StateContexts;
import net.sandius.rembulan.lib.*;
import net.sandius.rembulan.load.LoaderException;
import net.sandius.rembulan.runtime.LuaFunction;
import org.bukkit.Bukkit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * 用户包脚本运行时<br>
 * 持有全服共享的 Lua 状态上下文与脚本编译加载器，为每个包构建隔离的脚本环境并执行其入口脚本：
 * <ul>
 *     <li>共享一个{@link StateContext}：所有包的 Lua 值在同一宇宙，跨包 require 的模块表才能传递</li>
 *     <li>每包独立的环境表（{@code _ENV}）：全局变量互相隔离，A 包看不到 B 包的全局量</li>
 *     <li>标准库白名单：basic、string、table、math、utf8、coroutine；
 *         io、os、debug 一概不装，basic 装入的{@code dofile}/{@code loadfile}随即删除</li>
 *     <li>插件 API 以一组全局表暴露：{@code voidairrace}仅承载元数据，函数按类别分入各全局表（构成见{@link PluginApi}）</li>
 * </ul>
 * 入口脚本执行失败（编译错误、运行错误、指令预算耗尽）视为整包加载失败（见{@link #executeEntry}）
 * <p>
 * 本类非线程安全：所有 Lua 相关操作只在服务器主线程发生（由{@link ScriptCallGate}断言）
 * */
public class PackageScriptService implements Module {
    /** 包 Id → 包环境表（全局表）。仅登记加载成功的包 */
    private final @NonNull Map<@NonNull String, @NonNull Table> envs = new HashMap<>();

    /** 模块缓存键（{@code pkgid:path}）→ 已编译的模块主函数，跨包共享 */
    private final @NonNull Map<@NonNull String, @NonNull LuaFunction> moduleFnCache = new HashMap<>();

    /** 全服共享的 Lua 状态上下文 */
    private final @NonNull StateContext stateContext = StateContexts.newDefaultInstance();

    /** 用户脚本编译加载器：编译产物为 JVM 类，类名以固定前缀加自增序号保证唯一（chunk 名只是定位报错的标签） */
    private final @NonNull CompilerChunkLoader chunkLoader =
            CompilerChunkLoader.of(PackageScriptService.class.getClassLoader(), "voidairrace_user_script");

    @Override
    public @NonNull Collection<@NonNull Class<? extends Module>> getRequiredModules() {
        return List.of();
    }

    /** 插件禁用时执行 */
    private void onUnload() {
        envs.clear();
        moduleFnCache.clear();
    }

    /**
     * 编译并执行一个用户包的入口脚本<br>
     * 执行前先构建包环境（标准库白名单、插件 API、require）；脚本运行期间可 require
     * 自身（经环境直连引用）与其他已加载包的模块
     *
     * @param pkg             目标包（元数据已通过校验）
     * @param packageResolver 包 Id → 已加载包对象的解析函数，供 require 跨包引用，查无此包返回{@code null}
     *
     * @return 成功时携带入口脚本的返回值数组
     *
     * @throws UsrPackageException 读取入口脚本发生 IO 异常时抛出（意外失败），由调用方按意外失败处理
     * */
    public @NonNull ValueResult<Object[]> executeEntry(
            @NonNull UsrPackage pkg,
            @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver
    ) throws UsrPackageException {
        // 定位入口脚本：resolve 后 normalize 并确认仍在脚本目录内（zip-slip 防护）
        Path scriptsDir = pkg.scriptsDirectory();
        Path entryPath;
        try {
            entryPath = scriptsDir.resolve(pkg.entry()).normalize();
        } catch (InvalidPathException e) {
            return ValueResult.failure(
                    TranslateKeys.Addons.USR_PACKAGE_ENTRY_SCRIPT_FAILED,
                    null,
                    "入口脚本路径非法：" + pkg.entry(),
                    e
            );
        }
        if (!entryPath.startsWith(scriptsDir)) return ValueResult.failure(
                TranslateKeys.Addons.USR_PACKAGE_ENTRY_SCRIPT_FAILED,
                null,
                "入口脚本路径越出包脚本目录：" + pkg.entry(),
                null
        );
        if (!Files.isRegularFile(entryPath)) return ValueResult.failure(
                TranslateKeys.Addons.USR_PACKAGE_ENTRY_SCRIPT_FAILED,
                null,
                "入口脚本不存在：" + entryPath,
                null
        );

        String source;
        try {
            source = Files.readString(entryPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UsrPackageException(
                    "读取用户包入口脚本时发生 IO 异常：" + entryPath,
                    Component.translatable(TranslateKeys.Addons.USR_PACKAGE_ENTRY_SCRIPT_IO_ERROR)
            );
        }

        // 构建包环境
        Table env = buildEnv(pkg, packageResolver);

        // 编译入口脚本：chunk 名 = "包Id:入口路径"，保证全局唯一
        LuaFunction entryFn;
        try {
            entryFn = chunkLoader.loadTextChunk(new Variable(env), pkg.id() + ":" + pkg.entry(), source);
        } catch (LoaderException e) {
            discard(pkg.id());
            return ValueResult.failure(
                    TranslateKeys.Addons.USR_PACKAGE_ENTRY_SCRIPT_FAILED,
                    null,
                    "编译入口脚本失败：" + pkg.id() + ":" + pkg.entry()
                            + "（" + e.getLuaStyleErrorMessage() + "）",
                    e
            );
        }

        // 执行入口脚本：失败即整包失败
        ValueResult<Object[]> result = ScriptCallGate.call(stateContext, chunkLoader.getChunkClassLoader(), entryFn);
        if (!result.hasValue()) {
            discard(pkg.id());
            return result.causedBy(
                    TranslateKeys.Addons.USR_PACKAGE_ENTRY_SCRIPT_FAILED,
                    TranslateKeys.Addons.USR_PACKAGE_ENTRY_SCRIPT_FAILED
            );
        }
        envs.put(pkg.id(), env);
        return result;
    }

    /**
     * 作废一个包的脚本运行时状态：从环境记录表中移除其环境表（如已登记），
     * 并从共享函数缓存中驱逐其名下所有条目（缓存键以{@code pkgid:}开头）<br>
     * 用于两类场合：包加载失败的清理（此时环境尚未登记，移除为空操作）、已加载包的卸载
     * */
    public void discard(@NonNull String pkgId) {
        envs.remove(pkgId);
        moduleFnCache.keySet().removeIf(cacheKey -> cacheKey.startsWith(pkgId + ":"));
        // 有状态的脚本 API 类别（事件订阅、竞技场借据）各自监听本事件清理自己名下的资源
        Bukkit.getPluginManager().callEvent(new UsrPackageUnloadEvent(pkgId));
    }

    // ------ 包环境构建 ------

    /**
     * 为一个包构建隔离的脚本环境：安装标准库白名单、插件 API 与 require
     * */
    private @NonNull Table buildEnv(
            @NonNull UsrPackage pkg,
            @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver
    ) {
        Table env = stateContext.newTable();

        // 标准库白名单；print 输出重定向到插件日志
        BasicLib.installInto(stateContext, env, standardStreams(), chunkLoader);
        // basic 库装入的文件读取入口与 io/os 同罪，装完即删
        env.rawset(ByteString.of("dofile"), null);
        env.rawset(ByteString.of("loadfile"), null);
        StringLib.installInto(stateContext, env);
        TableLib.installInto(stateContext, env);
        MathLib.installInto(stateContext, env);
        Utf8Lib.installInto(stateContext, env);
        CoroutineLib.installInto(stateContext, env);

        // 插件全局表装入包环境：元数据表 voidairrace + 各函数类别表（player/event/arena/server/register/resource）
        PluginApi.install(stateContext, chunkLoader.getChunkClassLoader(), pkg, packageResolver, env);

        PackageRequire.installInto(chunkLoader, env, pkg, packageResolver, envs, moduleFnCache);
        return env;
    }

    /**
     * 标准流句柄（rembulan RuntimeEnvironment）：stdin 为空流，stdout 与 stderr 重定向到插件日志，
     * 作为 print 的输出去向<br>
     * 文件系统能力虽在此抽象中存在，但对应的标准库（io、os、dofile、loadfile）不会暴露给脚本
     * */
    private @NonNull RuntimeEnvironment standardStreams() {
        Logger logger = VoidAirRace.getInstance().getLogger();
        return RuntimeEnvironments.system(InputStream.nullInputStream(), new LogOutputStream(logger), new LogOutputStream(logger));
    }
}
