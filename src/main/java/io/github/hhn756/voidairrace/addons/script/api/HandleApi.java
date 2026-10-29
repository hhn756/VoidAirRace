package io.github.hhn756.voidairrace.addons.script.api;

import io.github.hhn756.voidairrace.addons.script.MutationAccess;
import io.github.hhn756.voidairrace.addons.script.api.match.MatchHandle;
import io.github.hhn756.voidairrace.addons.usrpackage.UsrPackage;
import io.github.hhn756.voidairrace.core.match.Match;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.Table;
import org.jspecify.annotations.NonNull;

/**
 * 句柄 API 类别入口：把引擎对象包装为脚本可用的普通 Lua 值（句柄表）<br>
 * 每类句柄独立成文件，本类只做统一静态入口：{@link #forMatch}、
 * {@link #forEndingMatch}、{@link #forEvent}，未来的 {@code forMap}/{@code forPlayer}
 * 等按同一形状增长
 * <p>
 * 句柄是无状态薄门面：每调用现造（无缓存、无生命周期监听）——生命周期
 * 全局化后，清扫职责全部落在全局 API 层（包生死）与各自资源管理器，
 * 句柄本身不再自管
 * */
public final class HandleApi {
    /** 静态工具类，不可实例化 */
    private HandleApi() {}

    /**
     * 构建指定比赛的句柄表（见{@link MatchHandle}；每调用现造，无缓存）<br>
     * 用于比赛回调（含地图 {@code on_start} 的启动阶段）：全部函数可用，比赛结束后句柄失效
     *
     * @param stateContext  共享 Lua 状态上下文
     * @param match         目标比赛
     * @param callerPackage 句柄所属地图的发起包（命名空间白名单的裁定基准）
     *
     * @return 该比赛的句柄表
     * */
    public static @NonNull Table forMatch(
            @NonNull StateContext stateContext,
            @NonNull Match match,
            @NonNull UsrPackage callerPackage
    ) {
        return MatchHandle.tableOf(stateContext, match, callerPackage, false, false);
    }

    /**
     * 构建收尾阶段的比赛句柄表（见{@link MatchHandle}；每调用现造，无缓存）<br>
     * 用于比赛已进入结束流程时的回调（如地图 {@code on_over}）：只放行查询类函数，
     * 操作类函数一律抛 Lua 错误
     *
     * @param stateContext  共享 Lua 状态上下文
     * @param match         目标比赛
     * @param callerPackage 句柄所属地图的发起包
     *
     * @return 该比赛的句柄表
     * */
    public static @NonNull Table forEndingMatch(
            @NonNull StateContext stateContext,
            @NonNull Match match,
            @NonNull UsrPackage callerPackage
    ) {
        return MatchHandle.tableOf(stateContext, match, callerPackage, true, false);
    }

    /**
     * 构建事件回调的比赛句柄表（见{@link MatchHandle}；每事件现造，无缓存）<br>
     * 本包对本场比赛有修改权（本场地图的注册包，或本场已启用 Lua 规则的来源包）时
     * 得到完整句柄，否则得到只读句柄——事件是包级广播，不能把别的包的比赛操作权一并交出
     *
     * @param stateContext  共享 Lua 状态上下文
     * @param match         事件相关的比赛
     * @param callerPackage 收到事件的包
     * @param allowEnding   是否放行收尾阶段（{@code ENDING}）的查询
     *
     * @return 该比赛的句柄表
     * */
    public static @NonNull Table forEvent(
            @NonNull StateContext stateContext,
            @NonNull Match match,
            @NonNull UsrPackage callerPackage,
            boolean allowEnding
    ) {
        return MatchHandle.tableOf(
                stateContext, match, callerPackage, allowEnding,
                !MutationAccess.hasMatchAuthority(match, callerPackage));
    }
}
