package io.github.hhn756.voidairrace.addons.usrpackage.script.api.register;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.addons.GameElementMeta;
import io.github.hhn756.voidairrace.addons.usrpackage.script.api.LuaHandles;
import io.github.hhn756.voidairrace.addons.usrpackage.script.api.ScriptCallGate;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.map.MapEntry;
import io.github.hhn756.voidairrace.core.map.PlayableGameMap;
import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.exception.RegistryException;
import io.github.hhn756.voidairrace.result.OperationResult;
import io.github.hhn756.voidairrace.result.ValueResult;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.load.ChunkClassLoader;
import net.sandius.rembulan.runtime.LuaFunction;
import org.jetbrains.annotations.Range;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 用户地图的 Java 适配器：把脚本注册的地图描述适配为{@link PlayableGameMap}<br>
 * 每场使用该地图的比赛经注册项实例化一个全新的本类实例；脚本提供的
 * {@code on_start}/{@code on_over} 回调在比赛开始/结束时经调用门触发，
 * 参数为该场比赛的句柄表（见{@link LuaHandles}）
 * <p>
 * 地图无异步初始化：包加载成功即视为就绪（{@link #isReady()}恒真）
 * <p>
 * 本类型是所有用户地图的统一类型：不同地图的差异完全由构造参数（元数据、回调）表达，
 * 不存在也不需要子类
 * <p>
 * 实例化约束：只能经{@link #entry}创建注册项（构造器包私有），地图实例只能由注册项实例化，
 * 其余代码不应也无法实例化本类
 * <p>
 * 归属元素注册类别：仅地图注册构造本类，与{@code RegisterApi}同包封装
 * */
final class LuaGameMap extends PlayableGameMap {
    /** 地图元数据（含 id、展示信息与标签） */
    private final @NonNull GameElementMeta meta;
    /** 允许参赛的最大队伍数量 */
    private final @Range(from = 1, to = Integer.MAX_VALUE) int maxTeams;
    /** 脚本提供的 on_start 函数，未提供为{@code null} */
    private final @Nullable LuaFunction onStartFn;
    /** 脚本提供的 on_over 函数，未提供为{@code null} */
    private final @Nullable LuaFunction onOverFn;
    /** 共享 Lua 状态上下文 */
    private final @NonNull StateContext stateContext;
    /** 编译产物类加载器，供调用门生成 Lua 风格调用栈 */
    private final @NonNull ChunkClassLoader chunkClassLoader;
    /** 日志与错误消息中的归属描述（包Id:地图键名） */
    private final @NonNull String owner;

    /**
     * 构造一个用户地图注册项<br>
     * 本方法是全项目唯一调用{@link LuaGameMap}构造器的位置：注册项的工厂在每次
     * {@link MapEntry#newInstance()} 时构造一个全新的 {@link LuaGameMap} 实例，
     * 地图实例化因此只能经注册项发生
     * <p>
     * 用户地图均为可游玩地图：注册项固定以可游玩形态创建，
     * {@code maxTeams} 同时作为注册项与地图实例的最大队伍数量
     *
     * @param meta             地图元数据
     * @param maxTeams         允许参赛的最大队伍数量
     * @param onStartFn        脚本提供的 on_start 函数，未提供为{@code null}
     * @param onOverFn         脚本提供的 on_over 函数，未提供为{@code null}
     * @param stateContext     共享 Lua 状态上下文
     * @param chunkClassLoader 编译产物类加载器
     * @param owner            归属描述（包Id:地图键名），用于日志与错误消息
     *
     * @return 新的用户地图注册项
     *
     * @throws RegistryException 如果注册项校验失败
     * */
    public static @NonNull MapEntry entry(
            @NonNull GameElementMeta meta,
            @Range(from = 1, to = Integer.MAX_VALUE) int maxTeams,
            @Nullable LuaFunction onStartFn,
            @Nullable LuaFunction onOverFn,
            @NonNull StateContext stateContext,
            @NonNull ChunkClassLoader chunkClassLoader,
            @NonNull String owner
    ) throws RegistryException {
        return new MapEntry(
                meta,
                () -> new LuaGameMap(meta, maxTeams, onStartFn, onOverFn, stateContext, chunkClassLoader, owner),
                true,
                maxTeams
        );
    }

    /**
     * 构造一个用户地图实例<br>
     * 仅限本类{@link #entry}调用
     *
     * @param meta              地图元数据
     * @param maxTeams          允许参赛的最大队伍数量
     * @param onStartFn         脚本提供的 on_start 函数，未提供为{@code null}
     * @param onOverFn          脚本提供的 on_over 函数，未提供为{@code null}
     * @param stateContext      共享 Lua 状态上下文
     * @param chunkClassLoader  编译产物类加载器
     * @param owner             归属描述（包Id:地图键名），用于日志与错误消息
     * */
    LuaGameMap(
            @NonNull GameElementMeta meta,
            @Range(from = 1, to = Integer.MAX_VALUE) int maxTeams,
            @Nullable LuaFunction onStartFn,
            @Nullable LuaFunction onOverFn,
            @NonNull StateContext stateContext,
            @NonNull ChunkClassLoader chunkClassLoader,
            @NonNull String owner
    ) {
        this.meta = meta;
        this.maxTeams = maxTeams;
        this.onStartFn = onStartFn;
        this.onOverFn = onOverFn;
        this.stateContext = stateContext;
        this.chunkClassLoader = chunkClassLoader;
        this.owner = owner;
    }

    @Override
    public boolean isReady() {
        // 地图无异步初始化，包加载成功即就绪
        return true;
    }

    @Override
    public @NonNull OperationResult start(@NonNull Match match) {
        if (onStartFn == null) return OperationResult.success();
        // 调用门成功一律携带返回值数组（on_start 无返回值协议，丢弃即可），
        // 失败一律无用户消息（reasonKey 为 null）——causedBy 恒走 unknown 分支，两键相同
        return ScriptCallGate.call(stateContext, chunkClassLoader, onStartFn, LuaHandles.forMatch(stateContext, match))
                .causedBy(TranslateKeys.Map.LUA_MAP_START_FAILED, TranslateKeys.Map.LUA_MAP_START_FAILED)
                .toOperation();
    }

    @Override
    public @NonNull OperationResult over(@NonNull Match match) {
        if (onOverFn == null) return OperationResult.success();
        ValueResult<Object[]> result = ScriptCallGate.call(
                stateContext, chunkClassLoader, onOverFn, LuaHandles.forMatch(stateContext, match));
        if (!result.hasValue()) {
            // 收尾阶段失败只记日志，不阻断比赛结束流程（对齐规则 on_disable 的处理）
            VoidAirRace.getInstance().getLogger().warning(
                    "用户地图 on_over 调用失败：" + owner + "（" + result.techMessage() + "）"
            );
        }
        return OperationResult.success();
    }

    @Override
    public @Range(from = 1, to = Integer.MAX_VALUE) int maxTeams() {
        return maxTeams;
    }

    @Override
    public @NonNull GameElementMeta getElementMeta() {
        return meta;
    }
}
