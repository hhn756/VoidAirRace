package io.github.hhn756.voidairrace.addons.script.api.config;

import io.github.hhn756.voidairrace.addons.usrpackage.UsrPackage;
import io.github.hhn756.voidairrace.infrastructure.util.lua.ApiArgs;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.Table;
import net.sandius.rembulan.impl.NonsuspendableFunctionException;
import net.sandius.rembulan.runtime.*;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.function.Function;

/**
 * 包配置类别的 Lua API 入口：包环境（{@code _G}）全局表{@code config}下的
 * {@code declare} / {@code exists} / {@code get} / {@code set} / {@code reload} / {@code delete}<br>
 * 全部为静态函数，不引入句柄：配置 id 为{@code fileId}（本包）或{@code pkgid:fileId}（跨包读取，
 * 目标包须为已声明的直接依赖）；写操作只允许本包的配置
 * <p>
 * 本类是类别入口：装配期由{@code PluginApi}调用{@link #installInto}注入包环境
 * */
public final class ConfigApi {
    /** 静态工具类，不可实例化 */
    private ConfigApi() {}

    /**
     * 把本类别的 API 函数注入包脚本环境：建全局表{@code config}并注入全部函数
     *
     * @param stateContext    共享 Lua 状态上下文（建表用）
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
        Table config = stateContext.newTable();
        config.rawset(ByteString.of("declare"), new DeclareFunction(pkg, packageResolver));
        config.rawset(ByteString.of("exists"), new ExistsFunction(pkg, packageResolver));
        config.rawset(ByteString.of("get"), new GetFunction(pkg, packageResolver));
        config.rawset(ByteString.of("set"), new SetFunction(pkg, packageResolver));
        config.rawset(ByteString.of("reload"), new ReloadFunction(pkg, packageResolver));
        config.rawset(ByteString.of("delete"), new DeleteFunction(pkg, packageResolver));
        env.rawset(ByteString.of("config"), config);
    }

    // ------ 全局表 config 的 Java 函数 ------

    /**
     * {@code config.declare(id, schema)}：声明配置的键与默认值；重复声明以最后一次为准
     * */
    private static final class DeclareFunction extends AbstractFunction2 {
        private final @NonNull UsrPackage pkg;
        private final @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver;

        private DeclareFunction(
                @NonNull UsrPackage pkg,
                @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver
        ) {
            this.pkg = pkg;
            this.packageResolver = packageResolver;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1, @Nullable Object arg2)
                throws ResolvedControlThrowable {
            String spec = ApiArgs.expectString(arg1, "config.declare 的第 1 个参数（配置 id）");
            Table schema = ApiArgs.expectTable(arg2, "config.declare 的第 2 个参数（schema）", "表");
            UserConfigService.declare(pkg, spec, schema, packageResolver);
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws ResolvedControlThrowable {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code config.exists(id)}：判断配置文件在磁盘上是否存在
     * */
    private static final class ExistsFunction extends AbstractFunction1 {
        private final @NonNull UsrPackage pkg;
        private final @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver;

        private ExistsFunction(
                @NonNull UsrPackage pkg,
                @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver
        ) {
            this.pkg = pkg;
            this.packageResolver = packageResolver;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            String spec = ApiArgs.expectString(arg1, "config.exists 的第 1 个参数（配置 id）");
            context.getReturnBuffer().setTo(UserConfigService.exists(pkg, spec, packageResolver));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws ResolvedControlThrowable {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code config.get(id, path?)}：读取整份配置或指定路径的值；路径不存在返回 nil
     * */
    private static final class GetFunction extends AbstractFunction2 {
        private final @NonNull UsrPackage pkg;
        private final @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver;

        private GetFunction(
                @NonNull UsrPackage pkg,
                @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver
        ) {
            this.pkg = pkg;
            this.packageResolver = packageResolver;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1, @Nullable Object arg2)
                throws ResolvedControlThrowable {
            String spec = ApiArgs.expectString(arg1, "config.get 的第 1 个参数（配置 id）");
            String path = arg2 == null
                    ? null
                    : ApiArgs.expectString(arg2, "config.get 的第 2 个参数（路径）");
            Object value = UserConfigService.get(context, pkg, spec, path, packageResolver);
            if (value == null) {
                context.getReturnBuffer().setTo();
            } else {
                context.getReturnBuffer().setTo(value);
            }
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws ResolvedControlThrowable {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code config.set(id, path, value)}：写入指定路径并立即保存
     * */
    private static final class SetFunction extends AbstractFunction3 {
        private final @NonNull UsrPackage pkg;
        private final @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver;

        private SetFunction(
                @NonNull UsrPackage pkg,
                @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver
        ) {
            this.pkg = pkg;
            this.packageResolver = packageResolver;
        }

        @Override
        public void invoke(
                @NonNull ExecutionContext context,
                @Nullable Object arg1,
                @Nullable Object arg2,
                @Nullable Object arg3
        ) throws ResolvedControlThrowable {
            String spec = ApiArgs.expectString(arg1, "config.set 的第 1 个参数（配置 id）");
            String path = ApiArgs.expectString(arg2, "config.set 的第 2 个参数（路径）");
            UserConfigService.set(pkg, spec, path, arg3, packageResolver);
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws ResolvedControlThrowable {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code config.reload(id)}：驱逐内存缓存并从磁盘重新加载
     * */
    private static final class ReloadFunction extends AbstractFunction1 {
        private final @NonNull UsrPackage pkg;
        private final @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver;

        private ReloadFunction(
                @NonNull UsrPackage pkg,
                @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver
        ) {
            this.pkg = pkg;
            this.packageResolver = packageResolver;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            String spec = ApiArgs.expectString(arg1, "config.reload 的第 1 个参数（配置 id）");
            UserConfigService.reload(pkg, spec, packageResolver);
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws ResolvedControlThrowable {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code config.delete(id)}：删除配置文件、内存缓存与键声明
     * */
    private static final class DeleteFunction extends AbstractFunction1 {
        private final @NonNull UsrPackage pkg;
        private final @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver;

        private DeleteFunction(
                @NonNull UsrPackage pkg,
                @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver
        ) {
            this.pkg = pkg;
            this.packageResolver = packageResolver;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            String spec = ApiArgs.expectString(arg1, "config.delete 的第 1 个参数（配置 id）");
            UserConfigService.delete(pkg, spec, packageResolver);
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws ResolvedControlThrowable {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }
}
