package io.github.hhn756.voidairrace.core.addons.usrpackage.script;

import io.github.hhn756.voidairrace.core.addons.usrpackage.UsrPackage;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.LuaRuntimeException;
import net.sandius.rembulan.Table;
import net.sandius.rembulan.Variable;
import net.sandius.rembulan.compiler.CompilerChunkLoader;
import net.sandius.rembulan.load.LoaderException;
import net.sandius.rembulan.runtime.*;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 包脚本的{@code require}实现（纯 Java）<br>
 * require 需要<strong>执行</strong>模块主函数并等待其返回值，而模块执行期间可能让出（协程），
 * 因此实现为<strong>可恢复函数</strong>，分两个阶段（惯用法与 rembulan 标准库的{@code pcall}一致）：
 * <ul>
 *     <li>{@link RequireFunction#invoke}：规范化模块名 → 查执行缓存 → 编译（或命中函数缓存）→
 *         预占执行缓存后发起模块调用；调用同步完成时立即收尾，暂停时以缓存键为挂起状态上抛</li>
 *     <li>{@link RequireFunction#resume}：从暂停点继续，模块调用的结果已在返回缓冲区，
 *         凭挂起状态中的缓存键回写执行缓存并返回</li>
 * </ul>
 * 模块名与缓存的约定（见文档“用户包”）：
 * <ul>
 *     <li>相对路径：解析到<strong>本包</strong>的{@code /scripts/}下</li>
 *     <li>{@code pkgid:path} 形式：解析到对应包的{@code /scripts/}下，即跨包引用</li>
 *     <li>函数缓存（{@code fnCache}）键为{@code pkgid:path}，全服唯一：同一模块全服只编译一次，
 *         其运行环境绑定<strong>所属包</strong>的环境（模块内的全局量属于所属包）</li>
 *     <li>执行缓存（{@code loaded}）每包一份，记录模块的<strong>执行结果</strong>：
 *         同一包内重复 require 直接取缓存；循环依赖期间以{@code true}占位防重入</li>
 * </ul>
 * 本类非线程安全（调用全部发生在主线程）
 * */
final class PackageRequire {

    private PackageRequire() {}

    /**
     * 在指定包环境内装配{@code require}
     *
     * @param loader   用户脚本 chunk 加载器
     * @param env      目标包环境（全局表），即所属包的环境
     * @param owner    require 所属的包
     * @param resolver 包 Id → 已加载包对象的解析函数，查无此包返回{@code null}
     * @param envs     包 Id → 已加载包环境的记录表，供跨包 require 查询目标包环境
     * @param fnCache  模块缓存键 → 已编译函数的缓存表，跨包共享
     * */
    static void install(
            @NonNull CompilerChunkLoader loader,
            @NonNull Table env,
            @NonNull UsrPackage owner,
            @NonNull Function<@NonNull String, @Nullable UsrPackage> resolver,
            @NonNull Map<@NonNull String, @NonNull Table> envs,
            @NonNull Map<@NonNull String, @NonNull LuaFunction> fnCache
    ) {
        env.rawset(ByteString.of("require"), new RequireFunction(loader, env, owner, resolver, envs, fnCache));
    }

    /**
     * require 的 Java 实现，可暂停（模块执行期间可能让出）
     * */
    private static final class RequireFunction extends AbstractFunction1 {
        private final @NonNull CompilerChunkLoader loader;
        /** 所属包的环境直连引用：自包模块不经 {@code envs} 查找（所属包尚未登记时也能 require 自身模块） */
        private final @NonNull Table ownerEnv;
        private final @NonNull UsrPackage owner;
        private final @NonNull Function<@NonNull String, @Nullable UsrPackage> resolver;
        private final @NonNull Map<@NonNull String, @NonNull Table> envs;
        private final @NonNull Map<@NonNull String, @NonNull LuaFunction> fnCache;

        /** 本包的模块执行缓存：缓存键 → 执行结果；循环依赖期间为占位 {@code true} */
        private final @NonNull Map<@NonNull String, @Nullable Object> loaded = new HashMap<>();

        private RequireFunction(
                @NonNull CompilerChunkLoader loader,
                @NonNull Table ownerEnv,
                @NonNull UsrPackage owner,
                @NonNull Function<@NonNull String, @Nullable UsrPackage> resolver,
                @NonNull Map<@NonNull String, @NonNull Table> envs,
                @NonNull Map<@NonNull String, @NonNull LuaFunction> fnCache
        ) {
            this.loader = loader;
            this.ownerEnv = ownerEnv;
            this.owner = owner;
            this.resolver = resolver;
            this.envs = envs;
            this.fnCache = fnCache;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) throws ResolvedControlThrowable {
            String cacheKey = canonicalKey(expectModuleName(arg1));

            Object cached = loaded.get(cacheKey);
            if (cached != null) {
                context.getReturnBuffer().setTo(cached);
                return;
            }

            LuaFunction fn = compileModule(cacheKey);
            // 预占缓存：循环依赖时第二次 require 拿到占位值而非重入
            loaded.put(cacheKey, Boolean.TRUE);

            try {
                // 执行模块主函数，传入调用者书写的原始模块名（与 Lua require 语义一致）
                Dispatch.call(context, fn, arg1);
            } catch (UnresolvedControlThrowable ct) {
                // 模块执行期间发生暂停：挂起状态携带缓存键，恢复时凭它回写缓存
                throw ct.resolve(this, cacheKey);
            }
            finish(context, cacheKey);
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws ResolvedControlThrowable {
            // 从暂停点继续：模块调用已完成，结果已在返回缓冲区
            if (!(suspendedState instanceof String cacheKey)) {
                throw new IllegalStateException("require 恢复时挂起状态缺失");
            }
            finish(context, cacheKey);
        }

        /**
         * 收尾：把模块调用结果写入执行缓存，并作为 require 的返回值<br>
         * 与 Lua 惯用写法{@code loaded[key] = result ~= nil and result or true}语义一致：
         * 模块无返回值（nil）或返回{@code false}时缓存记为{@code true}，require 的返回值保持原样
         * */
        private void finish(@NonNull ExecutionContext context, @NonNull String cacheKey) {
            Object result = context.getReturnBuffer().get0();
            loaded.put(cacheKey, result == null || Boolean.FALSE.equals(result) ? Boolean.TRUE : result);
            context.getReturnBuffer().setTo(result);
        }

        /**
         * 规范化模块名为缓存键：缺省补本包 Id 前缀与{@code .lua}后缀，
         * 使同一模块的不同书写形式（如{@code util/math_ext}与{@code demo:util/math_ext}）命中同一缓存
         * */
        private @NonNull String canonicalKey(@NonNull String name) {
            String key = name.indexOf(':') >= 0 ? name : owner.id() + ":" + name;
            return key.endsWith(".lua") ? key : key + ".lua";
        }

        /**
         * 编译指定缓存键的模块并返回其主函数；命中函数缓存时直接返回缓存
         * */
        private @NonNull LuaFunction compileModule(@NonNull String cacheKey) {
            String pkgId;
            String relPath;
            int separator = cacheKey.indexOf(':');
            if (separator >= 0) {
                pkgId = cacheKey.substring(0, separator);
                relPath = cacheKey.substring(separator + 1);
            } else {
                pkgId = owner.id();
                relPath = cacheKey;
            }
            if (pkgId.isBlank()) throw new LuaRuntimeException("require 的包 Id 不能为空：" + cacheKey);

            String normalizedPath = normalizeScriptPath(relPath, cacheKey);
            LuaFunction cached = fnCache.get(cacheKey);
            if (cached != null) return cached;

            // 定位所属包与其脚本目录：本包经直连引用取环境，其他包经解析函数与记录表查找
            Table env;
            Path scriptsDir;
            if (pkgId.equals(owner.id())) {
                env = ownerEnv;
                scriptsDir = owner.root().path().resolve("scripts");
            } else {
                UsrPackage target = resolver.apply(pkgId);
                if (target == null) throw new LuaRuntimeException("require 引用了未加载的用户包：" + pkgId);
                env = envs.get(pkgId);
                if (env == null) throw new LuaRuntimeException("用户包缺少脚本环境：" + pkgId);
                scriptsDir = target.root().path().resolve("scripts");
            }

            // 定位脚本文件：resolve 后 normalize 并确认仍在脚本目录内（zip-slip 防护）
            Path scriptPath = scriptsDir.resolve(normalizedPath).normalize();
            if (!scriptPath.startsWith(scriptsDir)) {
                throw new LuaRuntimeException("require 的脚本路径越出包脚本目录：" + cacheKey);
            }
            if (!Files.isRegularFile(scriptPath)) {
                throw new LuaRuntimeException("require 找不到脚本文件：" + cacheKey);
            }

            String source;
            try {
                source = Files.readString(scriptPath, StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new LuaRuntimeException("读取脚本文件失败：" + cacheKey + "（" + e.getMessage() + "）");
            }

            LuaFunction fn;
            try {
                fn = loader.loadTextChunk(new Variable(env), cacheKey, source);
            } catch (LoaderException e) {
                throw new LuaRuntimeException("编译脚本失败：" + cacheKey + "（" + e.getLuaStyleErrorMessage() + "）");
            }
            fnCache.put(cacheKey, fn);
            return fn;
        }

        /**
         * 校验并规整脚本相对路径：禁止越目录与绝对路径，缺省补{@code .lua}后缀
         * */
        private static @NonNull String normalizeScriptPath(@NonNull String relPath, @NonNull String original) {
            if (relPath.isBlank() || relPath.contains("..") || relPath.contains("\\")
                    || relPath.startsWith("/") || relPath.endsWith("/")) {
                throw new LuaRuntimeException("require 的脚本路径非法：" + original);
            }
            return relPath.endsWith(".lua") ? relPath : relPath + ".lua";
        }

        /**
         * 校验参数为 Lua 字符串并转为 Java 字符串
         * */
        private static @NonNull String expectModuleName(@Nullable Object arg) {
            if (arg instanceof ByteString bs) return bs.decode();
            throw new LuaRuntimeException("require 的参数必须是字符串");
        }
    }
}
