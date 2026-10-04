package io.github.hhn756.voidairrace.core.addons.usrpackage.script;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.constants.Categories;
import io.github.hhn756.voidairrace.constants.Plugin;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.addons.GameElementMeta;
import io.github.hhn756.voidairrace.core.addons.UserRule;
import io.github.hhn756.voidairrace.core.addons.usrpackage.PackageRoot;
import io.github.hhn756.voidairrace.core.addons.usrpackage.UsrPackage;
import io.github.hhn756.voidairrace.core.matchrule.RuleEntry;
import io.github.hhn756.voidairrace.exception.UsrPackageException;
import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import io.github.hhn756.voidairrace.infrastructure.registry.DefaultSubtable;
import io.github.hhn756.voidairrace.infrastructure.registry.Registry;
import io.github.hhn756.voidairrace.result.ValueResult;
import net.kyori.adventure.text.Component;
import net.sandius.rembulan.*;
import net.sandius.rembulan.compiler.CompilerChunkLoader;
import net.sandius.rembulan.env.RuntimeEnvironment;
import net.sandius.rembulan.env.RuntimeEnvironments;
import net.sandius.rembulan.impl.NonsuspendableFunctionException;
import net.sandius.rembulan.impl.StateContexts;
import net.sandius.rembulan.lib.*;
import net.sandius.rembulan.load.LoaderException;
import net.sandius.rembulan.runtime.*;
import org.bukkit.NamespacedKey;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 用户包脚本运行时<br>
 * 持有全服共享的 Lua 状态上下文与脚本编译加载器，为每个包构建隔离的脚本环境并执行其入口脚本：
 * <ul>
 *     <li>共享一个{@link StateContext}：所有包的 Lua 值在同一宇宙，跨包 require 的模块表才能传递</li>
 *     <li>每包独立的环境表（{@code _ENV}）：全局变量互相隔离，A 包看不到 B 包的全局量</li>
 *     <li>标准库白名单：basic、string、table、math、utf8、coroutine；
 *         io、os、debug 一概不装，basic 装入的{@code dofile}/{@code loadfile}随即删除</li>
 *     <li>插件 API 以全局表{@code voidairrace}暴露（见{@link #buildPluginApi}），
 *         自身资源经其{@code read_asset}/{@code list_assets}访问，自身及其他包脚本经{@code require}引用</li>
 * </ul>
 * 入口脚本执行失败（编译错误、运行错误、指令预算耗尽）视为整包加载失败：包环境不予登记，
 * 执行期间已编译的模块全部从共享缓存驱逐，调用方不得登记该包
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

    /** 用户脚本编译加载器：编译产物为 JVM 类，类名按包 Id 前缀全局唯一 */
    private final @NonNull CompilerChunkLoader chunkLoader =
            CompilerChunkLoader.of(PackageScriptService.class.getClassLoader(), "voidairrace_user_script");

    /** Java 调 Lua 的唯一门：主线程断言、指令预算、异常映射 */
    private final @NonNull ScriptCallGate gate = new ScriptCallGate(chunkLoader.getChunkClassLoader());

    @Override
    public Collection<Class<? extends Module>> getRequiredModules() {
        return List.of();
    }

    /** 插件启用时执行 */
    private void onLoad() {}

    /** 插件禁用时执行 */
    private void onUnload() {
        envs.clear();
        moduleFnCache.clear();
    }

    /**
     * 编译并执行一个用户包的入口脚本<br>
     * 执行前先构建包环境（标准库白名单、插件 API、require）；脚本运行期间可 require
     * 自身（经环境直连引用）与其他已加载包的模块；包环境在入口脚本执行成功后才登记
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
        Path scriptsDir = pkg.root().path().resolve("scripts");
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

        // 构建包环境（登记在案，require 才能引用到自身）
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

        // 执行入口脚本：失败即整包失败，仅驱逐执行期间已编译的模块（环境尚未登记，无需清理）
        ValueResult<Object[]> result = gate.call(stateContext, entryFn);
        if (!result.hasValue()) {
            discard(pkg.id());
            return result;
        }
        // 先加载后登记：加载成功才登记包环境
        envs.put(pkg.id(), env);
        return result;
    }

    /**
     * 作废一个（加载失败的）包已编译的模块：从共享函数缓存中驱逐其名下所有条目
     * （缓存键以{@code pkgid:}开头）。环境本就未登记，随失败包自然失去引用，无需处理
     * */
    private void discard(@NonNull String pkgId) {
        moduleFnCache.keySet().removeIf(cacheKey -> cacheKey.startsWith(pkgId + ":"));
    }

    // ------ 包环境构建 ------

    /**
     * 为一个包构建隔离的脚本环境：安装标准库白名单、插件 API 与 require，并登记到环境记录表
     * */
    private @NonNull Table buildEnv(
            @NonNull UsrPackage pkg,
            @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver
    ) {
        Table env = stateContext.newTable();

        // 标准库白名单；print 输出重定向到插件日志
        BasicLib.installInto(stateContext, env, scriptRuntimeEnvironment(), chunkLoader);
        StringLib.installInto(stateContext, env);
        TableLib.installInto(stateContext, env);
        MathLib.installInto(stateContext, env);
        Utf8Lib.installInto(stateContext, env);
        CoroutineLib.installInto(stateContext, env);
        // basic 库装入的文件读取入口与 io/os 同罪，装完即删
        env.rawset(ByteString.of("dofile"), null);
        env.rawset(ByteString.of("loadfile"), null);

        env.rawset(ByteString.of("voidairrace"), buildPluginApi(pkg));

        PackageRequire.install(chunkLoader, env, pkg, packageResolver, envs, moduleFnCache);
        return env;
    }

    /**
     * 脚本运行时环境：print 的输出与错误流向插件日志，而非服务端标准输出<br>
     * 文件系统等能力虽然由本环境提供，但对应的标准库（io、os、dofile、loadfile）不会暴露给脚本
     * */
    private @NonNull RuntimeEnvironment scriptRuntimeEnvironment() {
        Logger logger = VoidAirRace.getInstance().getLogger();
        return RuntimeEnvironments.system(InputStream.nullInputStream(), new LogOutputStream(logger), new LogOutputStream(logger));
    }

    /**
     * 构建一个包的插件 API 全局表{@code voidairrace}<br>
     * 资源访问函数绑定到此包的包根，路径一律相对包根，且越出包根即报错
     * */
    private @NonNull Table buildPluginApi(@NonNull UsrPackage pkg) {
        Table api = stateContext.newTable();
        api.rawset(ByteString.of("api_version"), (long) Plugin.API_VERSION);
        api.rawset(ByteString.of("register"), new RegisterFunction(pkg));
        api.rawset(ByteString.of("read_asset"), new ReadAssetFunction(pkg.root()));
        api.rawset(ByteString.of("list_assets"), new ListAssetsFunction(pkg.root()));
        return api;
    }

    // ------ 资源路径与参数的共用辅助 ------

    /**
     * 校验参数为 Lua 字符串并转为 Java 字符串
     *
     * @param arg  参数原值
     * @param what 参数用途说明，用于错误消息
     * */
    private static @NonNull String expectString(@Nullable Object arg, @NonNull String what) {
        if (arg instanceof ByteString bs) return bs.decode();
        throw new LuaRuntimeException(what + "必须是字符串");
    }

    /**
     * 解析包内相对路径为实际路径，非法或越出包根即抛 Lua 错误（zip-slip 防护）：
     * 禁止空路径、{@code ..}、绝对路径、反斜杠与冒号
     * */
    private static @NonNull Path resolveWithinRoot(@NonNull Path root, @NonNull String relPath) {
        if (relPath.isBlank() || relPath.contains("..") || relPath.contains("\\")
                || relPath.startsWith("/") || relPath.endsWith("/") || relPath.contains(":")) {
            throw new LuaRuntimeException("资产路径非法：" + relPath);
        }
        Path resolved;
        try {
            resolved = root.resolve(relPath).normalize();
        } catch (InvalidPathException e) {
            throw new LuaRuntimeException("资产路径非法：" + relPath);
        }
        if (!resolved.startsWith(root)) throw new LuaRuntimeException("资产路径越出包根：" + relPath);
        return resolved;
    }

    // ------ 规则注册 ------

    /**
     * 规则键名的合法字符集：小写字母、数字、下划线、连字符<br>
     * 键名经包 Id 前缀组成 NamespacedKey，字符集从严
     * */
    private static final Pattern RULE_KEY_PATTERN = Pattern.compile("[a-z0-9_-]+");

    /**
     * 处理{@code register("rule", descriptor)}：解析并校验规则描述符，
     * 构造用户规则注册项登记到注册表<br>
     * 校验失败抛 Lua 错误，使整包加载失败
     *
     * @param pkg        发起注册的包
     * @param descriptor 规则描述符表
     * */
    private void registerRule(@NonNull UsrPackage pkg, @NonNull Table descriptor) {
        // id：只写键名，自动加包前缀——跨包唯一性自动成立，脚本无法冒充其他包
        String keyName = optString(descriptor, "id");
        if (keyName == null) throw new LuaRuntimeException("规则描述符的字段 “id” 必填");
        if (!RULE_KEY_PATTERN.matcher(keyName).matches()) {
            throw new LuaRuntimeException("规则描述符的字段 “id” 只允许小写字母、数字、下划线和连字符：" + keyName);
        }
        NamespacedKey ruleId;
        try {
            ruleId = new NamespacedKey(pkg.id(), keyName);
        } catch (IllegalArgumentException e) {
            throw new LuaRuntimeException("规则描述符的字段 “id” 非法：" + keyName + "（" + e.getMessage() + "）");
        }

        // 展示信息（全部可选）；空数组视同未提供，避免 mainName 取首元素时越界
        List<@NonNull String> tags = optStringList(descriptor, "tags");
        GameElementMeta meta = new GameElementMeta(
                ruleId,
                toComponents(optStringList(descriptor, "names")),
                toComponents(optStringList(descriptor, "description")),
                toComponents(optStringList(descriptor, "authors")),
                toComponent(optString(descriptor, "display_version")),
                optPositiveLong(descriptor, "version"),
                toComponents(optStringList(descriptor, "links"))
        );

        // 行为回调（全部可选；未提供时沿用 MatchRule 的默认生命周期行为）
        LuaFunction onEnable = optFunction(descriptor, "on_enable");
        LuaFunction onDisable = optFunction(descriptor, "on_disable");
        LuaFunction tick = optFunction(descriptor, "tick");

        // 登记：重复 id 显式报错（子表 add 的不覆盖语义会静默吞掉重复项，不能依赖）
        DefaultSubtable<RuleEntry<?>, NamespacedKey> subtable =
                Registry.getInstance().category(Categories.RULE);
        if (subtable.isRegistered(ruleId)) {
            throw new LuaRuntimeException("规则 id 已被注册：" + ruleId);
        }
        subtable.add(UserRule.entry(
                meta,
                tags == null ? List.of() : tags,
                pkg,
                () -> new LuaRuleCallback(gate, stateContext, onEnable, onDisable, tick, pkg.id() + ":" + keyName)
        ));
        VoidAirRace.getInstance().getLogger().info("用户包 " + pkg.id() + " 注册比赛规则：" + ruleId);
    }

    /**
     * 读取描述符中可选的字符串字段：缺省返回{@code null}；存在但不是字符串即抛 Lua 错误
     * */
    private static @Nullable String optString(@NonNull Table descriptor, @NonNull String field) {
        Object value = descriptor.rawget(ByteString.of(field));
        if (value == null) return null;
        if (!(value instanceof ByteString bs)) {
            throw new LuaRuntimeException("规则描述符的字段 “" + field + "” 必须是字符串");
        }
        return bs.decode();
    }

    /**
     * 读取描述符中可选的正整数字段（Lua 整数）：缺省返回{@code null}；类型不对或小于 1 即抛 Lua 错误
     * */
    private static @Nullable Long optPositiveLong(@NonNull Table descriptor, @NonNull String field) {
        Object value = descriptor.rawget(ByteString.of(field));
        if (value == null) return null;
        if (!(value instanceof Long version) || version < 1) {
            throw new LuaRuntimeException("规则描述符的字段 “" + field + "” 必须是正整数");
        }
        return version;
    }

    /**
     * 读取描述符中可选的字符串数组字段（Lua 数组表，键 1..n 连续）：
     * 缺省返回{@code null}；存在但不是表或含非字符串项即抛 Lua 错误
     * */
    private static @Nullable List<@NonNull String> optStringList(@NonNull Table descriptor, @NonNull String field) {
        Object value = descriptor.rawget(ByteString.of(field));
        if (value == null) return null;
        if (!(value instanceof Table table)) {
            throw new LuaRuntimeException("规则描述符的字段 “" + field + "” 必须是字符串数组");
        }
        List<@NonNull String> list = new ArrayList<>();
        long index = 1;
        while (true) {
            Object item = table.rawget(index);
            if (item == null) break;
            if (!(item instanceof ByteString bs)) {
                throw new LuaRuntimeException("规则描述符的字段 “" + field + "” 的第 " + index + " 项必须是字符串");
            }
            list.add(bs.decode());
            index++;
        }
        return list;
    }

    /**
     * 读取描述符中可选的函数字段：缺省返回{@code null}；存在但不是函数即抛 Lua 错误
     * */
    private static @Nullable LuaFunction optFunction(@NonNull Table descriptor, @NonNull String field) {
        Object value = descriptor.rawget(ByteString.of(field));
        if (value == null) return null;
        if (!(value instanceof LuaFunction fn)) {
            throw new LuaRuntimeException("规则描述符的字段 “" + field + "” 必须是函数");
        }
        return fn;
    }

    /** 字符串列表转组件列表；null 或空表均转{@code null}（视同未提供） */
    private static @Nullable List<@NonNull Component> toComponents(@Nullable List<@NonNull String> strings) {
        if (strings == null || strings.isEmpty()) return null;
        return strings.stream().map(string -> (Component) Component.text(string)).toList();
    }

    /** 字符串转组件；null 或空白转{@code null}（视同未提供） */
    private static @Nullable Component toComponent(@Nullable String string) {
        if (string == null || string.isBlank()) return null;
        return Component.text(string);
    }

    // ------ voidairrace 表的 Java 函数 ------

    /**
     * {@code voidairrace.register(category, descriptor)}：向注册表注册一个游戏元素<br>
     * 当前仅开放 “rule” 类别（比赛规则）；校验失败抛 Lua 错误，使整包加载失败
     * */
    private final class RegisterFunction extends AbstractFunction2 {
        /** 发起注册的包 */
        private final @NonNull UsrPackage pkg;

        private RegisterFunction(@NonNull UsrPackage pkg) {
            this.pkg = pkg;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1, @Nullable Object arg2) {
            String category = expectString(arg1, "voidairrace.register：第 1 个参数（注册类别）");
            if (!(arg2 instanceof Table descriptor)) {
                throw new LuaRuntimeException("voidairrace.register：第 2 个参数（元素描述符）必须是表");
            }
            switch (category) {
                case "rule" -> registerRule(pkg, descriptor);
                default -> throw new LuaRuntimeException(
                        "voidairrace.register：注册类别 “" + category + "” 尚未开放（本版本仅支持 \"rule\"）");
            }
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws ResolvedControlThrowable {
            // 本函数不会暂停，没有可恢复的挂起状态
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code voidairrace.read_asset(path)}：读取包内资产文件，返回二进制安全的 Lua 字符串
     * */
    private static final class ReadAssetFunction extends AbstractFunction1 {
        private final @NonNull PackageRoot root;

        private ReadAssetFunction(@NonNull PackageRoot root) {
            this.root = root;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            String relPath = expectString(arg1, "voidairrace.read_asset 的路径参数");
            Path file = resolveWithinRoot(root.path(), relPath);
            if (!Files.isRegularFile(file)) {
                throw new LuaRuntimeException("资产文件不存在：" + relPath);
            }
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (IOException e) {
                throw new LuaRuntimeException("读取资产文件失败：" + relPath + "（" + e.getMessage() + "）");
            }
            context.getReturnBuffer().setTo(ByteString.copyOf(bytes));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws ResolvedControlThrowable {
            // 本函数不会暂停，没有可恢复的挂起状态
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code voidairrace.list_assets(path)}：列出包内资产目录的直属条目，
     * 返回字符串数组，目录条目名以{@code /}结尾；{@code path} 省略时列出包根
     * */
    private static final class ListAssetsFunction extends AbstractFunction1 {
        private final @NonNull PackageRoot root;

        private ListAssetsFunction(@NonNull PackageRoot root) {
            this.root = root;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            String relPath = arg1 == null ? "" : expectString(arg1, "voidairrace.list_assets 的路径参数");
            Path dir = relPath.isEmpty() ? root.path() : resolveWithinRoot(root.path(), relPath);
            if (!Files.isDirectory(dir)) {
                throw new LuaRuntimeException("资产目录不存在：" + relPath);
            }

            List<String> names = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
                for (Path entry : entries) {
                    String name = entry.getFileName().toString();
                    if (Files.isDirectory(entry)) name = name + "/";
                    names.add(name);
                }
            } catch (IOException e) {
                throw new LuaRuntimeException("读取资产目录失败：" + relPath + "（" + e.getMessage() + "）");
            }
            Collections.sort(names);

            Table result = context.newTable();
            long index = 1;
            for (String name : names) {
                result.rawset(index++, ByteString.of(name));
            }
            context.getReturnBuffer().setTo(result);
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws ResolvedControlThrowable {
            // 本函数不会暂停，没有可恢复的挂起状态
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * 脚本标准输出的重定向管道：按行解码为 UTF-8 文本写入插件日志<br>
     * print 的内容由 Lua 侧逐段写入，此处只负责攒行
     * */
    private static final class LogOutputStream extends OutputStream {
        private final @NonNull Logger logger;
        private final @NonNull ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        private LogOutputStream(@NonNull Logger logger) {
            this.logger = logger;
        }

        @Override
        public void write(int b) {
            if (b == '\n') {
                flushLine();
            } else {
                buffer.write(b);
            }
        }

        @Override
        public void flush() {
            flushLine();
        }

        private void flushLine() {
            if (buffer.size() == 0) return;
            logger.info("[用户包脚本] " + buffer.toString(StandardCharsets.UTF_8));
            buffer.reset();
        }
    }
}
