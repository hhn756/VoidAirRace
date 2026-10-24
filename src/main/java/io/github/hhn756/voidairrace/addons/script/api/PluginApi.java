package io.github.hhn756.voidairrace.addons.script.api;

import io.github.hhn756.voidairrace.addons.script.api.event.EventApi;
import io.github.hhn756.voidairrace.addons.script.api.register.RegisterApi;
import io.github.hhn756.voidairrace.addons.usrpackage.UsrPackage;
import io.github.hhn756.voidairrace.constants.Plugin;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.Table;
import net.sandius.rembulan.load.ChunkClassLoader;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.function.Function;

/**
 * 插件脚本 API 的装配协调器：把插件提供的全局表装入包环境（{@code _G}），并注入
 * 元数据表——本类不承载任何函数实现
 * <p>
 * 装入的全局表（函数按类别归表，各类别入口类所在包见括号标注）：
 * <ul>
 *     <li>{@code voidairrace}：插件元数据——目前仅{@code api_version}（API 版本号常量），
 *         根表不承载任何函数</li>
 *     <li>{@code player}：玩家查询 —— {@code position} / {@code team_of}（{@code api}）</li>
 *     <li>{@code event}：引擎事件订阅 —— {@code on} / {@code off}（{@code api}）</li>
 *     <li>{@code arena}：竞技场借用 —— {@code borrow}（{@code api}）</li>
 *     <li>{@code server}：服务器级操作 —— {@code broadcast}（{@code api}）</li>
 *     <li>{@code register}：元素注册 —— {@code map} / {@code rule} / {@code tag}
 *         （{@code api.register}）</li>
 *     <li>{@code resource}：资源访问 —— {@code read_asset} / {@code list_assets}
 *         （{@code api}）</li>
 * </ul>
 * 「不随意操作」的边界即这套全局表的整体：表上没有的操作脚本做不了。
 * 各类别入口的统一装配约定：静态方法{@code installInto(...)}把本类别表注入目标环境。
 * 函数对象分两类：无状态的（不持有包、句柄或状态上下文）共用一份常量实例；
 * 有状态的随表现造，保证包与句柄之间互不串状态
 * <p>
 * 本类是无状态的静态工具类，不可实例化；所有操作只在服务器主线程发生
 * */
public final class PluginApi {
    /** 静态工具类，不可实例化 */
    private PluginApi() {}

    /**
     * 向一个包的脚本环境（{@code _G}）装入插件全局表：元数据表{@code voidairrace}
     * 与各函数类别表<br>
     * 资源访问函数绑定到此包：自身资源直接访问，跨包访问经依赖白名单裁定
     *
     * @param stateContext     共享 Lua 状态上下文（各全局表与回调句柄表都在此宇宙创建）
     * @param chunkClassLoader 编译产物类加载器（构造规则回调时传给适配器，供调用门生成 Lua 风格调用栈）
     * @param pkg              环境所属的包
     * @param packageResolver  包 Id → 已加载包对象的解析函数，查无此包返回{@code null}
     * @param env              包脚本环境（{@code _G}），全局表装入目标
     * */
    public static void install(
            @NonNull StateContext stateContext,
            @NonNull ChunkClassLoader chunkClassLoader,
            @NonNull UsrPackage pkg,
            @NonNull Function<@NonNull String, @Nullable UsrPackage> packageResolver,
            @NonNull Table env
    ) {
        Table meta = stateContext.newTable();
        meta.rawset(ByteString.of("api_version"), (long) Plugin.LUA_API_VERSION);
        env.rawset(ByteString.of("voidairrace"), meta);
        PlayerApi.installInto(stateContext, env);
        EventApi.installInto(stateContext, chunkClassLoader, pkg, env);
        ArenaApi.installInto(stateContext, pkg, packageResolver, env);
        ServerApi.installInto(stateContext, env);
        RegisterApi.installInto(stateContext, chunkClassLoader, pkg, env);
        ResourceApi.installInto(stateContext, pkg, packageResolver, env);
    }
}
