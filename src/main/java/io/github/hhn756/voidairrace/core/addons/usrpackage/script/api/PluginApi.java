package io.github.hhn756.voidairrace.core.addons.usrpackage.script.api;

import io.github.hhn756.voidairrace.constants.Plugin;
import io.github.hhn756.voidairrace.core.addons.usrpackage.UsrPackage;
import io.github.hhn756.voidairrace.core.addons.usrpackage.script.api.register.RegisterApi;
import io.github.hhn756.voidairrace.core.addons.usrpackage.script.api.resource.ResourceApi;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.Table;
import net.sandius.rembulan.load.ChunkClassLoader;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.function.Function;

/**
 * 插件 API 全局表{@code voidairrace}的装配协调器：建表、注入版本号，并按类别
 * 委托各入口类注入函数实现——本类不承载任何函数实现
 * <p>
 * API 表的构成（键为常量，类别实现在各自子包）：
 * <ul>
 *     <li>{@code api_version}：插件 API 版本号（常量）</li>
 *     <li>{@code register(category, descriptor)}：注册游戏元素 —— 元素注册类别
 *         （{@code api.register}）</li>
 *     <li>{@code read_asset(path)} / {@code list_assets(path?)}：读取 / 列出包资产 ——
 *         资源访问类别（{@code api.resource}）</li>
 * </ul>
 * 各类别入口的统一装配约定：静态方法{@code installInto(...)}把本类别的函数注入目标表
 * <p>
 * 本类是无状态的静态工具类，不可实例化；所有操作只在服务器主线程发生
 * */
public final class PluginApi {
    /** 静态工具类，不可实例化 */
    private PluginApi() {}

    /**
     * 构建一个包的插件 API 全局表{@code voidairrace}<br>
     * 资源访问函数绑定到此包：自身资源直接访问，跨包访问经依赖白名单裁定
     *
     * @param stateContext     共享 Lua 状态上下文（API 表与回调句柄表都在此宇宙创建）
     * @param chunkClassLoader 编译产物类加载器（构造规则回调时传给适配器，供调用门生成 Lua 风格调用栈）
     * @param pkg              API 表所属的包
     * @param packageResolver  包 Id → 已加载包对象的解析函数，查无此包返回{@code null}
     * */
    public static @NonNull Table build(
            @NonNull StateContext stateContext,
            @NonNull ChunkClassLoader chunkClassLoader,
            @NonNull UsrPackage pkg,
            @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver
    ) {
        Table api = stateContext.newTable();
        api.rawset(ByteString.of("api_version"), (long) Plugin.API_VERSION);
        RegisterApi.installInto(stateContext, chunkClassLoader, pkg, api);
        ResourceApi.installInto(pkg, packageResolver, api);
        return api;
    }
}
