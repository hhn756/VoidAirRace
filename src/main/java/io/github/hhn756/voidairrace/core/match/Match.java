package io.github.hhn756.voidairrace.core.match;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.match.componentbase.*;
import io.github.hhn756.voidairrace.event.MatchOverEvent;
import io.github.hhn756.voidairrace.event.MatchStartedEvent;
import io.github.hhn756.voidairrace.infrastructure.config.Config;
import io.github.hhn756.voidairrace.infrastructure.config.ConfigDefinition;
import io.github.hhn756.voidairrace.infrastructure.config.ConfigKey;
import io.github.hhn756.voidairrace.infrastructure.config.YamlConfig;
import io.github.hhn756.voidairrace.infrastructure.config.files.PublicFiles;
import io.github.hhn756.voidairrace.infrastructure.util.TypeReference;
import io.github.hhn756.voidairrace.result.OperationResult;
import io.github.hhn756.voidairrace.result.ValueResult;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * 代表一局正在进行或已安排的比赛<br>
 * 包含比赛的配置、所用的组件等数据
 *
 *
 */
public class Match {
    /** 标记此赛实例是否已进行过游戏，防止复用实例 */
    private boolean used = false;

    /** 比赛状态 */
    private @NonNull MatchState state = MatchState.SCHEDULED;

    /** 比赛所用的配置 */
    private final @NonNull MatchConfig config;

    /** 比赛记录文件定义 */
    private static final @NonNull ConfigDefinition<YamlConfig> recordFile = new ConfigDefinition<>(
            PublicFiles.TEMP_DIR + "match_record",
            new ConfigKey<?>[0],
            new TypeReference<>(){}
    );
    /** 比赛记录文件实例 */
    private YamlConfig recordInst;

    /**
     * 构造一场新的比赛（不会自动开始）
     *
     * @param config 指定新比赛所用配置
     */
    private Match(@NonNull MatchConfig config) {
        this.config = config;
    }

    /**
     * 构造一场新的比赛（不会自动开始）
     *
     * @param config 指定新比赛所用配置
     */
    public static @NonNull ValueResult<Match> create(@NonNull MatchConfig config) {
        // 防止重复使用配置实例
        if (config.isUsed()) return ValueResult.failure(
                TranslateKeys.Match.CREATE_CONFIG_IS_USED
        );
        config.use();

        return ValueResult.success(new Match(config));
    }

    // ---------- 开始比赛 ----------

    /**
     * 使比赛开始
     *
     * @param args 传递给所有组件的参数。组件收到的参数可以为{@code null}（即传入列表不包含对应组件的参数）<br>
     *             此方法会将每个参数对象传递给其{@link CustomData#source()}返回类型的组件
     *
     * @return 开始结果。失败时携带前置校验失败原因或组件安装失败的原因（含组件给出的文案与安装异常）
     * */
    public @NonNull OperationResult start(@Nullable CustomData... args) {
        // 防止重复开始一局比赛
        if (state != MatchState.SCHEDULED) return OperationResult.failure(
                TranslateKeys.Match.START_INVALID_STATE);
        // 防止复用比赛实例
        if (used) return OperationResult.failure(
                TranslateKeys.Match.START_INSTANCE_IS_USED);

        // 标记使用
        used = true;

        // 更新状态
        state = MatchState.STARTING;

        // 创建记录
        createRecord();

        // 安装组件
        ValueResult<Map<DataKey<?>, CustomData>> installComponentsResult = installComponents(args);
        if (installComponentsResult
                instanceof ValueResult.Failed(var key, var userDetail, var techDetail, var cause)) {
            // 安装结果里的失败信息已是最终形式（含 START_INSTALL_FAILED 键），原样转为无值结果
            return new OperationResult.Failed(key, userDetail, techDetail, cause);
        }
        if (!(installComponentsResult instanceof ValueResult.WithValue(var startContext))) {
            // installComponents 成功必须产出上下文映射，走到这里属于实现错误
            return new OperationResult.Failed(
                    TranslateKeys.Match.START_INSTALL_FAILED,
                    null,
                    "installComponents成功但未产出开始上下文映射",
                    new IllegalStateException("installComponents成功但未产出开始上下文映射")
            );
        }

        // 更新状态
        state = MatchState.IN_PROGRESS;

        // 通知其他模块
        new MatchStartedEvent(this, startContext).callEvent();

        // 告知调用者开始成功
        return OperationResult.success();
    }

    /**
     * 内部方法，开始比赛流程的一部分。用于创建比赛记录
     * */
    private void createRecord() {
        recordInst = Config.getInstance().getYmlConfig(recordFile);
    }

    /**
     * 内部方法，开始比赛流程的一部分。用于安装比赛组件
     *
     * @param args 传递给各组件的开始参数
     *
     * @return 成功时携带开始上下文映射（可以为空映射）；任一组件安装失败时携带失败原因
     * */
    @NonNull ValueResult<Map<DataKey<?>, CustomData>> installComponents(@Nullable CustomData... args) {
        // 开始上下文
        Map<DataKey<?>, CustomData> startContext = new HashMap<>();
        // 参数映射
        HashMap<Class<? extends MatchComp>, CustomData> argMap = new HashMap<>();
        if (args != null) {
            // 记录所有非null的参数值到 argMap
            for (CustomData arg : args) {
                if (arg != null) {
                    argMap.put(arg.source(), arg);
                }
            }
        }

        // 按安装优先级降序排序（优先级高的先安装）
        List<? extends StartableComp<?, ?>> sortedComponents = config.getAllComponents().values().stream()
                .filter(comp -> comp instanceof StartableComp<?,?>)
                .map(comp -> (StartableComp<?,?>) comp)
                .sorted((a, b)
                        -> Integer.compare(b.getInstallPriority(), a.getInstallPriority()))
                .toList();

        List<StartableComp<?, ?>> installed = new ArrayList<>();
        for (StartableComp<?, ?> component : sortedComponents) {
            CustomData arg = argMap.get(component.getClass());
            InstallAttempt attempt = installComponentSafely(component, arg, installed, startContext);
            if (!attempt.success()) {
                // 安装失败后逆安装顺序卸载已安装的组件
                rollback(installed, this);
                return ValueResult.failure(
                        TranslateKeys.Match.START_INSTALL_FAILED,
                        attempt.message(),  // 组件失败结果自带的用户文案，可能为null
                        attempt.techMessage(), // 组件失败结果自带的技术性消息，可能为null
                        attempt.cause()     // 安装抛出的异常，可能为null
                );
            }
        }

        return ValueResult.success(startContext);
    }

    /**
     * 单次组件安装尝试的结果（类内部使用），把失败信息传递给{@link Match#installComponents(CustomData...)}
     *
     * @param success 本次安装是否成功
     * @param result  组件{@link StartableComp#install(Match, CustomData)}返回的结果（其抛出异常时为{@code null}）
     * @param cause   安装抛出的异常（未抛出时为{@code null}）
     * */
    private record InstallAttempt(
            boolean success,
            @Nullable ValueResult<? extends CustomData> result,
            @Nullable Exception cause
    ) {
        /**
         * @return 组件失败结果自带的用户文案；无结果或无文案时为{@code null}
         * */
        private @Nullable Component message() {
            return result == null ? null : result.message();
        }

        /**
         * @return 组件失败结果自带的技术性消息（仅日志）；无结果或无消息时为{@code null}
         * */
        private @Nullable String techMessage() {
            return result == null ? null : result.techMessage();
        }
    }

    /**
     * 内部方法。尝试安装一个指定组件
     *
     * @param startable 要安装的组件
     *
     * @return 本次安装尝试的结果（成功与否与失败信息）
     * */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private @NonNull InstallAttempt installComponentSafely(
            @NonNull StartableComp<?, ?> startable,
            @NonNull CustomData arg,
            @NonNull List<@NonNull StartableComp<?, ?>> installed,
            @NonNull Map<@NonNull DataKey<?>, @NonNull CustomData> startContext) {

        ValueResult<? extends CustomData> result = null;
        Exception thrownException = null;
        try {
            result = ((StartableComp) startable).install(this, arg);
        } catch (Exception e) {
            thrownException = e;
        }

        // 检查是否失败（失败结果或抛出异常）
        boolean failed = thrownException != null || !result.isSuccess();
        if (failed) {
            // 安装失败，回滚并传递失败原因或错误信息
            rollbackOne(startable, arg, result, thrownException);
            return new InstallAttempt(false, result, thrownException);
        }

        // 安装成功
        installed.add(startable);
        if (result instanceof ValueResult.WithValue(var ctx)) {
            // 组件产出了开始上下文
            startContext.put(startable.getSCK(), ctx);
        }
        return new InstallAttempt(true, result, null);
    }

    /**
     * 内部方法，用于在组件加载失败后捕获{@link StartableComp}类型组件的开始参数类型以执行回滚操作
     * */
    @SuppressWarnings("unchecked")
    private <SA extends CustomData, SC extends CustomData>void rollbackOne(
            @NonNull StartableComp<@NonNull SA, @NonNull SC> startable,
            @NonNull CustomData installArg,
            @Nullable ValueResult<? extends CustomData> installResult,
            @Nullable Exception exceptionOfInstall) {
        startable.rollback(
                this,
                (SA) installArg,
                (ValueResult<SC>) installResult,
                exceptionOfInstall
        );
    }

    /**
     * 内部方法，用于在开始游戏加载组件失败后逆向卸载已安装的组件
     * */
    private void rollback(@NonNull List<StartableComp<?, ?>> installed, @NonNull Match match) {
        // 按安装的相反顺序卸载
        for (int i = installed.size() - 1; i >= 0; i--) {
            StartableComp<?, ?> startableComp = installed.get(i);
            try {
                // 调用正常的 uninstall 进行清理（不关心返回值，忽略失败）
                if (startableComp instanceof EndableComp<?,?> endableComp) {
                    endableComp.uninstall(match, null);
                }
            } catch (Exception e) {
                if (startableComp instanceof MatchComp matchComp) {
                    // 记录日志，继续执行
                    VoidAirRace.getInstance().getLogger().warning(
                            "回滚时卸载组件 '"
                                    + matchComp.getMeta().names()
                                    + "' 失败：" + e.getMessage());
                }
            }
        }
    }

    // ---------- 结束比赛 ----------

    /**
     * 使比赛结束
     *
     * @param args 传递给所有组件的结束参数。组件收到的参数可以为 {@code null}（即传入列表不包含对应组件的参数）。
     *             此方法会将每个参数对象传递给其 {@link CustomData#source()} 返回类型的组件
     */
    public @NonNull OperationResult stop(@Nullable CustomData... args) {
        // 如果未开始
        if (state != MatchState.IN_PROGRESS) return OperationResult.failure(
                TranslateKeys.Match.STOP_INVALID_STATE);

        state = MatchState.ENDING;

        // 结束上下文
        Map<DataKey<?>, CustomData> endContext = new HashMap<>();

        // 参数映射
        HashMap<Class<? extends MatchComp>, CustomData> argMap = new HashMap<>();
        if (args != null) {
            for (CustomData arg : args) {
                if (arg != null) {
                    argMap.put(arg.source(), arg);
                }
            }
        }

        // 按卸载优先级降序排序（优先级高的先卸载）
        List<? extends EndableComp<?, ?>> sortedComponents = config.getAllComponents().values().stream()
                .filter(comp -> comp instanceof EndableComp<?, ?>)
                .map(comp -> (EndableComp<?, ?>) comp)
                .sorted((a, b)
                        -> Integer.compare(b.getUninstallPriority(), a.getUninstallPriority()))
                .toList();

        for (EndableComp<?, ?> endable : sortedComponents) {
            uninstallOne(endable, argMap, endContext);
            // 卸载失败也继续执行，不阻塞其他组件卸载
        }

        // 比赛实例不可复用，不用重置到初始状态

        new MatchOverEvent(this, endContext).callEvent();
        return OperationResult.success();
    }

    /**
     * 内部方法，用于在比赛结束时卸载单个组件
    */
    @SuppressWarnings("unchecked")
    private <EA extends CustomData, EC extends CustomData> void uninstallOne(
            @NonNull EndableComp<EA, EC> component,
            @NonNull HashMap<@NonNull Class<? extends MatchComp>, CustomData> argMap,
            @NonNull Map<@NonNull DataKey<?>, @NonNull CustomData> endContext
    ) {
        Logger logger = VoidAirRace.getInstance().getLogger();
        // 获取该组件对应的结束参数（可能为 null）
        CustomData arg = argMap.get(component.getClass());
        ValueResult<EC> result = null;
        Component compName = null;
        // 一定满足条件
        if (component instanceof MatchComp matchComp) {
            compName = matchComp.getMeta().mainName();
        }

        try {
            // 直接调用 uninstall，不处理过程中的问题（组件自行记录日志或处理）
            result = component.uninstall(this, (EA) arg);
            if (!result.isSuccess()) {
                logger.warning(
                        "'卸载比赛组件 '"
                        + compName
                        + "' 失败"
                );
            }
        } catch (Exception e) {
            logger.warning(
                    "卸载比赛组件 '"
                            + compName
                            + "' 时发生异常：" + e.getMessage());
        }
        if (result instanceof ValueResult.WithValue(var ctx)) {
            // 组件产出了结束上下文
            DataKey<?> key = component.getECK();
            endContext.put(key, ctx);
        }
    }

    // ---------- API ----------

    /**
     * @return 比赛所用的配置
     */
    public @NonNull MatchConfig config() {
        return config;
    }

    /**
     * 获取比赛中的比赛组件实例
     *
     * @param componentType 目标组件的类型
     *
     * @return 比赛中的组件实例
     *
     * @see MatchConfig#getComp(Class)
     * */
    public <C extends MatchComp> @NonNull C comp(@NonNull Class<C> componentType) {
        return config().getComp(componentType);
    }

    /**
     * 获取比赛配置中的数据
     *
     * @see MatchConfig#dataOf(ConfigurableComp)
     * */
    public <K extends CustomData> @Nullable K configOf(ConfigurableComp<?, K> component) {
        return config.dataOf(component.getConfigKey());
    }

    /**
     * 获取比赛配置中的数据
     * 
     * @see MatchConfig#dataOf(DataKey)
     * */
    public @Nullable <K extends CustomData> K configOf(DataKey<K> key) {
        return config.dataOf(key);
    }

    /**
     * @return 此比赛当前状态
     *
     * @see MatchState
     * */
    public @NonNull MatchState state() {
        return state;
    }

    /**
     * 对记录段执行的操作，允许抛出受检异常
     * */
    @FunctionalInterface
    public interface RecordAction {
        /**
         * 对指定比赛记录段执行操作
         * */
        void apply(ConfigurationSection section) throws Exception;
    }

    /**
     * 在lambda中操作比赛记录的指定部分（下称“记录段”），然后自动保存（原子性）比赛记录到文件
     *
     * @param module 指定模块
     * @param fn 对模块的记录段的操作<br>
     *           其接收一个代表模块的记录段的{@link ConfigurationSection}，
     *           该段与内存中的记录树共用同一引用，不存在副本概念，对其读写会直接作用于记录本身<br>
     *           注意：参数受运行时代理管辖，只能在函数体内使用。将参数或其派生对象（子段、
     *           {@code getParent}/{@code getRoot}等）复制留存、在lambda结束后调用，
     *           都会立即抛出{@link IllegalStateException}；
     *           列表型读取结果（如{@code getList}）为副本，直接修改副本不会同步到记录，需通过{@code set}写回
     *
     * @throws Exception 如果传入的{@code fn}抛出了异常此方法内不会处理，直接向外传播
     * */
    public void record(@NonNull NamespacedKey module,
                       @NonNull RecordAction fn)
            throws Exception {
        // 获取模块的记录段
        String key = module.getNamespace()
                + "___"
                + module.getKey();
        ConfigurationSection section;
        if (!recordInst.contains(key)) {
            section = recordInst.createSection(key);
        } else {
            section = recordInst.getConfigurationSection(key);
        }

        // 处理
        // 管辖标记：本次访问记录对象调用期间为true，结束（含异常）后置false，代理随之失效
        AtomicBoolean live = new AtomicBoolean(true);
        try {
            fn.apply(recordGuard(section, live)); // 调用者传入的函数可能抛出异常
        } finally {
            live.set(false); // 到期：传入的段及其派生子段立即失效
        }

        // 自动保存
        recordInst.saveAtomic();
    }

    /**
     * 为记录段创建受管辖的运行时代理（{@link java.lang.reflect.Proxy}）<br>
     * 管辖规则：
     * <ul>
     *     <li>{@code live}为false（回调已结束）后，对该段及其全部派生子段的任何方法调用抛出
     *     {@link IllegalStateException}，使“在record回调外使用记录段”立即暴露而非静默读写；</li>
     *     <li>拒绝{@code getParent}/{@code getRoot}，防止借树结构向上取到未受管辖的节点绕过检测；</li>
     *     <li>{@code getConfigurationSection}/{@code createSection}返回的子段继续包装，管辖随树向下传播；</li>
     *     <li>列表型返回值（如{@code getList}）转为副本，防止留存活引用后绕过代理直接改动记录。</li>
     * </ul>
     * 注意：{@code set}等操作仍委托到真实节点上执行，代理只做检查与转发，
     * 不改变记录段与记录树共用同一引用的语义。
     *
     * @param real 真实记录段
     * @param live 管辖生效标记，同一次record的全部代理共享
     *
     * @return 受管辖的代理段
     * */
    private static ConfigurationSection recordGuard(ConfigurationSection real, AtomicBoolean live) {
        return (ConfigurationSection) Proxy.newProxyInstance(
                ConfigurationSection.class.getClassLoader(),
                new Class<?>[]{ConfigurationSection.class},
                (proxy, method, args) -> {
                    String name = method.getName();

                    // 到期检查：回调结束后经留存引用进来的调用全部在此拦截
                    if (!live.get()) {
                        throw new IllegalStateException(
                                "比赛记录段在record回调外被使用: " + name
                                        + " @ " + real.getCurrentPath());
                    }

                    // 阻断向上逃逸出本段作用域的入口
                    if (method.getParameterCount() == 0
                            && ("getParent".equals(name) || "getRoot".equals(name))) {
                        throw new IllegalStateException(
                                "比赛记录段禁止经" + name + "逃逸出作用域: "
                                        + real.getCurrentPath());
                    }

                    Object result;
                    try {
                        result = method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        // 解包还原，保持与真实记录段一致的异常语义
                        throw e.getCause() != null ? e.getCause() : e;
                    }

                    // 子段继续受管辖（共享live）
                    if (result instanceof ConfigurationSection) {
                        return recordGuard((ConfigurationSection) result, live);
                    }
                    // 活引用列表转副本（getStringList等本就是副本，此处覆盖getList/getMapList等）
                    if (result instanceof List<?>) {
                        return new ArrayList<Object>((List<?>) result);
                    }
                    return result;
                });
    }

}
