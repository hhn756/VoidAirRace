package io.github.hhn756.voidairrace.core.addons.usrpackage.script.api.resource;

import io.github.hhn756.voidairrace.core.addons.usrpackage.UsrPackage;
import io.github.hhn756.voidairrace.core.addons.usrpackage.script.api.PackageAccess;
import io.github.hhn756.voidairrace.infrastructure.util.lua.ApiArgs;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.LuaRuntimeException;
import net.sandius.rembulan.Table;
import net.sandius.rembulan.impl.NonsuspendableFunctionException;
import net.sandius.rembulan.runtime.AbstractFunction1;
import net.sandius.rembulan.runtime.ExecutionContext;
import net.sandius.rembulan.runtime.ResolvedControlThrowable;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 资源访问类别的 Lua API 入口：{@code voidairrace.read_asset(path)} 与
 * {@code voidairrace.list_assets(path?)}<br>
 * 读取 / 列出包资产：自身资源直接访问，跨包访问 {@code pkgid:path}（须为已声明依赖）；
 * 路径语法与越界裁定统一由{@link PackageAccess}负责
 * <p>
 * 本类是类别入口：装配期由{@code PluginApi}调用{@link #installInto}注入 API 表
 * */
public final class ResourceApi {
    /** 静态工具类，不可实例化 */
    private ResourceApi() {}

    /**
     * 把本类别的 API 函数注入插件 API 表
     *
     * @param pkg             API 表所属的包
     * @param packageResolver 包 Id → 已加载包对象的解析函数，查无此包返回{@code null}
     * @param api             插件 API 表（{@code voidairrace}）
     * */
    public static void installInto(
            @NonNull UsrPackage pkg,
            @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver,
            @NonNull Table api
    ) {
        api.rawset(ByteString.of("read_asset"), new ReadAssetFunction(pkg, packageResolver));
        api.rawset(ByteString.of("list_assets"), new ListAssetsFunction(pkg, packageResolver));
    }

    /**
     * {@code voidairrace.read_asset(path)}：读取资产文件，返回二进制安全的 Lua 字符串<br>
     * 目标为本包或已声明依赖的包（{@code pkgid:path}），大小受{@link PackageAccess#MAX_FILE_BYTES}约束
     * */
    private static final class ReadAssetFunction extends AbstractFunction1 {
        private final @NonNull UsrPackage pkg;
        private final @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver;

        private ReadAssetFunction(
                @NonNull UsrPackage pkg,
                @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver
        ) {
            this.pkg = pkg;
            this.packageResolver = packageResolver;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            String spec = ApiArgs.expectString(arg1, "voidairrace.read_asset 的路径参数");
            PackageAccess.Target target = PackageAccess.resolve(pkg, spec, packageResolver);
            Path file = target.pkg().resolve(target.relPath());
            if (!Files.isRegularFile(file)) {
                throw new LuaRuntimeException("资产文件不存在：" + spec);
            }
            byte[] bytes = PackageAccess.readBytes(file, spec, PackageAccess.MAX_FILE_BYTES);
            context.getReturnBuffer().setTo(ByteString.copyOf(bytes));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws ResolvedControlThrowable {
            // 本函数不会暂停，没有可恢复的挂起状态
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code voidairrace.list_assets(path)}：列出资产目录的直属条目，
     * 返回字符串数组，目录条目名以{@code /}结尾；{@code path} 省略或为空时列出本包根；
     * 跨包写 {@code pkgid:path}（须为已声明依赖）
     * */
    private static final class ListAssetsFunction extends AbstractFunction1 {
        private final @NonNull UsrPackage pkg;
        private final @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver;

        private ListAssetsFunction(
                @NonNull UsrPackage pkg,
                @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver
        ) {
            this.pkg = pkg;
            this.packageResolver = packageResolver;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            // 省略参数或空串 = 列出本包根；否则经统一入口解析（支持跨包）
            Path dir;
            String spec = "";
            if (arg1 != null) {
                spec = ApiArgs.expectString(arg1, "voidairrace.list_assets 的路径参数");
                if (!spec.isEmpty()) {
                    PackageAccess.Target target = PackageAccess.resolve(pkg, spec, packageResolver);
                    dir = target.pkg().resolve(target.relPath());
                } else {
                    dir = pkg.path();
                }
            } else {
                dir = pkg.path();
            }
            if (!Files.isDirectory(dir)) {
                throw new LuaRuntimeException("资产目录不存在：" + spec);
            }

            List<String> names = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
                for (Path entry : entries) {
                    String name = entry.getFileName().toString();
                    if (Files.isDirectory(entry)) name = name + "/";
                    names.add(name);
                }
            } catch (IOException e) {
                throw new LuaRuntimeException("读取资产目录失败：" + spec + "（" + e.getMessage() + "）");
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
}
