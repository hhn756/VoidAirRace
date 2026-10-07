package io.github.hhn756.voidairrace.infrastructure.modules;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.infrastructure.util.ClassScanner;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.jspecify.annotations.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.logging.Logger;

/**
 * 模块工具类，统一承载两种“模块”的加载逻辑：
 * <ul>
 *   <li>引导阶段任务（{@link BootstrapStage}）：{@link #bootstrapAll(BootstrapContext)} 由 {@code Bootstrap}
 *       在插件引导阶段调用，扫描全部实现类并逐个执行一次 {@code onBootstrap}，无卸载概念</li>
 *   <li>普通模块（{@link Module}）：{@link #loadAll(Collection)} 在插件启用时调用，按前置声明拓扑排序加载；
 *       {@link #unloadAll()} 在插件禁用时逆序卸载</li>
 * </ul>
 * 供非模块代码获取普通模块单例的方法是 {@link #getModule(Class)}
 *
 * <p>除引导方法依赖 Paper 引导 API（{@code BootstrapContext} / {@code ComponentLogger}）外，
 * 其余部分只依赖 Java 标准库与 {@code VoidAirRace.getInstance().getLogger()}
 * */
public final class Modules {
    /**
     * 本次插件启用中所有已实例化的模块实例，按“首次接触顺序”排列，key 为模块类
     * */
    private static final @NonNull Map<Class<? extends Module>, Module> INSTANCES = new LinkedHashMap<>();

    /**
     * 模块类 -> 其 {@code getRequiredModules()} 声明的前置模块类集合
     * */
    private static final @NonNull Map<Class<? extends Module>, Set<Class<? extends Module>>> REQUIRED = new HashMap<>();

    /**
     * 实际执行过 {@code onLoad} 的模块，按加载顺序排列；失败回滚与正常卸载都取此列表的逆序
     * */
    private static final @NonNull List<Class<? extends Module>> LOADED = new ArrayList<>();

    /**
     * 本次插件启用的模块列表，用于把主类传入的列表跨插件生命周期保留下来
     * */
    private static List<Class<? extends Module>> declaredModules = List.of();

    private Modules() {
    }

    /**
     * 引导全部引导阶段任务：扫描 {@link BootstrapStage} 的全部实现类，逐个实例化并执行 {@code onBootstrap}
     *
     * <p>由 {@code Bootstrap} 在插件引导阶段调用一次；单个任务失败只记录错误并继续处理其余任务，不抛异常
     *
     * @param context 引导上下文
     * */
    public static synchronized void bootstrapAll(@NonNull BootstrapContext context) {
        ComponentLogger logger = context.getLogger();
        ClassLoader classLoader = Modules.class.getClassLoader();
        URL codeSourceUrl = Modules.class.getProtectionDomain().getCodeSource().getLocation();

        // 扫描所有实现了 BootstrapStage 的类
        Collection<Class<BootstrapStage>> stageClasses = ClassScanner.scanSubclasses(
                classLoader, codeSourceUrl, BootstrapStage.class, "io.github.hhn756.voidairrace");
        logger.debug("共扫描到 {} 个 BootstrapStage 实现：", stageClasses.size());
        for (Class<BootstrapStage> stageClass : stageClasses) {
            logger.debug(stageClass.getName());
        }

        // 实例化并调用
        for (Class<BootstrapStage> stageClass : stageClasses) {
            try {
                BootstrapStage stage = createInstance(stageClass);
                stage.onBootstrap(context);
                logger.debug("已引导: {}", stageClass.getSimpleName());
            } catch (Exception e) {
                logger.error("引导阶段任务 {} 执行失败", stageClass.getName(), e);
            }
        }
    }

    /**
     * 加载全部模块：实例化、检查前置与循环依赖、按拓扑顺序执行 {@code onLoad}
     * <p>
     * 任一模块的 {@code onLoad} 抛出异常时，按加载逆序回滚（先卸载刚失败的模块本身），回滚完成后抛出异常
     *
     * @param modules 待加载模块列表，顺序无关（加载顺序由依赖拓扑排序决定）
     *
     * @throws IllegalStateException 校验失败、循环依赖或模块加载失败
     * */
    public static synchronized void loadAll(Collection<? extends Class<? extends Module>> modules) {
        if (modules == null) throw new IllegalStateException("待加载模块列表为 null");
        if (!INSTANCES.isEmpty()) throw new IllegalStateException("模块已加载，不能在同一次插件启用中重复加载");

        declaredModules = List.copyOf(modules);
        logger().fine("模块加载器：本次启用声明 " + declaredModules.size() + " 个模块");

        List<Class<? extends Module>> order;
        try {
            // 1. 实例化所有模块，同时建立前置关系图（getRequiredModules 是实例方法，必须先有实例）
            for (Class<? extends Module> root : declaredModules) instantiateGraph(root);

            // 2. 拓扑排序，顺带完成循环依赖检查
            order = topoSort();

            // 3. 正式加载前，全量校验每个模块的生命周期方法与其参数，
            //    把“错误实现”挡在 onLoad 之前，避免状态写到一半才暴露
            for (Class<? extends Module> moduleClass : order) {
                validateLifecycleMethods(moduleClass);
            }
        } catch (Throwable failure) {
            resetState();
            throw new IllegalStateException("模块加载失败：" + failure.getMessage(), failure);
        }

        // 4. 按拓扑顺序加载（前置一定排在被依赖者之后被调用，即前置先加载）
        for (Class<? extends Module> moduleClass : order) {
            // 每“开始加载”一个模块就先登记，确保加载中途失败时它也在回滚范围内
            LOADED.add(moduleClass);
            try {
                invoke(moduleClass, "onLoad");
            } catch (Throwable failure) {
                logger().severe("模块 " + display(moduleClass) + " 的 onLoad 执行失败，开始回滚 " + LOADED.size() + " 个已加载模块");
                rollback();
                throw new IllegalStateException("模块 " + display(moduleClass) + " 加载失败，已回滚全部模块", failure);
            }
        }

        logger().fine("模块加载器：全部 " + LOADED.size() + " 个模块加载完成");
    }

    /**
     * 正常卸载：按加载逆序执行各模块的 {@code onUnload}
     *
     * 单个模块卸载失败只记录警告，继续卸载其余模块，最后不抛异常
     * */
    public static synchronized void unloadAll() {
        if (LOADED.isEmpty()) {
            resetState();
            return;
        }

        logger().fine("模块卸载器：按加载逆序卸载 " + LOADED.size() + " 个模块");
        List<Class<? extends Module>> snapshot = new ArrayList<>(LOADED);
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            Class<? extends Module> moduleClass = snapshot.get(i);
            try {
                invoke(moduleClass, "onUnload");
            } catch (Throwable failure) {
                logger().warning("模块 " + display(moduleClass) + " 的 onUnload 执行失败，已跳过并继续卸载其他模块：" + failure);
            }
        }

        resetState();
    }

    /**
     * 获取模块单例实例，供非模块代码调用
     *
     * @param moduleClass 模块类
     *
     * @return 模块实例
     *
     * @throws IllegalStateException 该模块未在本次启用中加载
     * */
    public static synchronized <T extends Module> T getModule(Class<T> moduleClass) {
        if (moduleClass == null) throw new IllegalStateException("moduleClass 为 null");
        Module instance = INSTANCES.get(moduleClass);
        if (instance == null) {
            throw new IllegalStateException("模块 " + display(moduleClass) + " 未加载：不在本次插件启用的模块列表中，或已随插件禁用卸载");
        }
        return moduleClass.cast(instance);
    }

    // ==================== 以下为私有静态内部辅助方法 ====================

    /**
     * 从 root 出发做可达闭包：逐个实例化模块，并记录其前置集合<br>
     * 声明为前置但未出现在待加载列表中的模块会被一并加载，否则前置关系无法成立
     * */
    private static void instantiateGraph(Class<? extends Module> root) {
        Deque<Class<? extends Module>> pending = new ArrayDeque<>();
        pending.push(root);

        while (!pending.isEmpty()) {
            Class<? extends Module> moduleClass = pending.pop();
            if (INSTANCES.containsKey(moduleClass)) continue;

            Module instance = createInstance(moduleClass);
            INSTANCES.put(moduleClass, instance);

            Collection<Class<? extends Module>> declared;
            try {
                declared = instance.getRequiredModules();
            } catch (Throwable failure) {
                throw new IllegalStateException("模块 " + display(moduleClass) + " 的 getRequiredModules 执行失败", failure);
            }

            Set<Class<? extends Module>> required = new LinkedHashSet<>();
            if (declared == null) {
                logger().warning("模块 " + display(moduleClass) + " 的 getRequiredModules 返回 null，按无前置处理");
            } else {
                for (Class<? extends Module> prerequisite : declared) {
                    if (prerequisite == null) {
                        throw new IllegalStateException("模块 " + display(moduleClass) + " 的前置集合包含 null");
                    }
                    required.add(prerequisite);
                    pending.push(prerequisite);
                }
            }
            REQUIRED.put(moduleClass, required);
        }
    }

    /**
     * 反射调用无参构造器创建实例（构造器可以是 private）；<br>
     * 供普通模块（{@link Module}）与引导阶段任务（{@link BootstrapStage}）共用
     * */
    private static <T> T createInstance(Class<? extends T> type) {
        if (type.isInterface() || java.lang.reflect.Modifier.isAbstract(type.getModifiers())) {
            throw new IllegalStateException("类 " + display(type) + " 是接口或抽象类，无法实例化");
        }

        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return type.cast(constructor.newInstance());
        } catch (NoSuchMethodException failure) {
            throw new IllegalStateException("类 " + display(type) + " 缺少无参构造器", failure);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException("类 " + display(type) + " 实例化失败", failure);
        }
    }

    /**
     * 深度优先拓扑排序：返回值中被依赖者在前、依赖者在后；发现回边即判定为循环依赖并报错
     * */
    private static List<Class<? extends Module>> topoSort() {
        // 0=未访问，1=在当前 DFS 栈上，2=已完成
        Map<Class<? extends Module>, Integer> state = new HashMap<>();
        List<Class<? extends Module>> order = new ArrayList<>();
        Deque<Class<? extends Module>> path = new ArrayDeque<>();

        for (Class<? extends Module> moduleClass : INSTANCES.keySet()) {
            visit(moduleClass, state, path, order);
        }
        return order;
    }

    private static void visit(
            Class<? extends Module> moduleClass,
            Map<Class<? extends Module>, Integer> state,
            Deque<Class<? extends Module>> path,
            List<Class<? extends Module>> order
    ) {
        Integer current = state.get(moduleClass);
        if (current != null && current == 2) return;
        if (current != null && current == 1) {
            throw new IllegalStateException("检测到循环依赖：" + describeCycle(path, moduleClass));
        }

        state.put(moduleClass, 1);
        path.push(moduleClass);
        for (Class<? extends Module> prerequisite : REQUIRED.getOrDefault(moduleClass, Set.of())) {
            visit(prerequisite, state, path, order);
        }
        path.pop();
        state.put(moduleClass, 2);
        order.add(moduleClass);
    }

    private static String describeCycle(Deque<Class<? extends Module>> path, Class<? extends Module> repeated) {
        // path 自 DFS 栈顶到栈底；反转后为“源头 -> ... -> 重复节点的前驱”，从环入口截断并闭合环
        List<Class<? extends Module>> chain = new ArrayList<>(path);
        Collections.reverse(chain);
        int start = chain.indexOf(repeated);
        chain = new ArrayList<>(chain.subList(Math.max(start, 0), chain.size()));
        chain.add(repeated);
        StringBuilder builder = new StringBuilder();
        for (Class<? extends Module> element : chain) {
            if (!builder.isEmpty()) builder.append(" -> ");
            builder.append(display(element));
        }
        return builder.toString();
    }

    /**
     * 加载前对一个模块做完整静态校验：<br>
     * {@code onLoad} / {@code onUnload} 各自必须恰好声明一个、返回 void，
     * 且每个参数都能从前置模块中唯一解析出注入目标
     * */
    private static void validateLifecycleMethods(Class<? extends Module> moduleClass) {
        Set<Class<? extends Module>> required = REQUIRED.getOrDefault(moduleClass, Set.of());
        for (String methodName : List.of("onLoad", "onUnload")) {
            Method method = findUniqueDeclaredMethod(moduleClass, methodName);
            Class<?>[] parameterTypes = method.getParameterTypes();
            for (int index = 0; index < parameterTypes.length; index++) {
                resolveArgument(moduleClass, methodName, index, parameterTypes[index], required);
            }
        }
    }

    /**
     * 解析并反射调用模块自身声明的 {@code onLoad} 或 {@code onUnload}
     * */
    private static void invoke(Class<? extends Module> moduleClass, String methodName) {
        Module self = INSTANCES.get(moduleClass);
        Method method = findUniqueDeclaredMethod(moduleClass, methodName);
        method.setAccessible(true);

        Class<?>[] parameterTypes = method.getParameterTypes();
        Object[] arguments = new Object[parameterTypes.length];
        Set<Class<? extends Module>> required = REQUIRED.getOrDefault(moduleClass, Set.of());
        for (int index = 0; index < parameterTypes.length; index++) {
            arguments[index] = resolveArgument(moduleClass, methodName, index, parameterTypes[index], required);
        }

        try {
            method.invoke(self, arguments);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("模块 " + display(moduleClass) + " 的 " + methodName + " 无法访问", failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("模块 " + display(moduleClass) + " 的 " + methodName + " 抛出异常", cause);
        }
    }

    /**
     * 防止错误实现：模块类自身声明（不含继承）的该方法必须恰好一个
     * */
    private static Method findUniqueDeclaredMethod(Class<? extends Module> moduleClass, String methodName) {
        List<Method> candidates = new ArrayList<>();
        for (Method method : moduleClass.getDeclaredMethods()) {
            if (method.isBridge() || method.isSynthetic()) continue;
            if (method.getName().equals(methodName)) candidates.add(method);
        }
        if (candidates.size() != 1) {
            throw new IllegalStateException(
                    "模块 " + display(moduleClass) + " 自身声明的 " + methodName + " 方法数量为 " + candidates.size() + "，必须恰好为 1");
        }
        Method method = candidates.get(0);
        if (method.getReturnType() != void.class) {
            throw new IllegalStateException("模块 " + display(moduleClass) + " 的 " + methodName + " 返回类型必须为 void");
        }
        return method;
    }

    /**
     * 为单个方法参数挑选要注入的前置模块实例
     * <p>
     * 参数类型必须是本模块某个前置模块类的父类或接口；再从已实例化的前置模块中取出所有可赋值给
     * 该参数类型的实例：恰好一个直接用，多个则优先取类型完全等于参数类型的那个，无法唯一确定时报错
     * */
    private static Module resolveArgument(
            Class<? extends Module> moduleClass,
            String methodName,
            int index,
            Class<?> parameterType,
            Set<Class<? extends Module>> required
    ) {
        List<Module> candidates = new ArrayList<>();
        for (Class<? extends Module> prerequisite : required) {
            if (!parameterType.isAssignableFrom(prerequisite)) continue;
            Module instance = INSTANCES.get(prerequisite);
            if (instance != null && parameterType.isInstance(instance)) candidates.add(instance);
        }

        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "模块 " + display(moduleClass) + " 的 " + methodName + " 第 " + index + " 个参数类型 "
                            + display(parameterType) + " 不是其任何前置模块的父类或接口。前置模块："
                            + describeClasses(required));
        }
        if (candidates.size() == 1) return candidates.get(0);

        for (Module candidate : candidates) {
            if (candidate.getClass() == parameterType) return candidate;
        }

        throw new IllegalStateException(
                "模块 " + display(moduleClass) + " 的 " + methodName + " 第 " + index + " 个参数类型 "
                        + display(parameterType) + " 存在 " + candidates.size()
                        + " 个可赋值的前置模块且无类型完全相等的实例，无法确定注入目标");
    }

    /**
     * 加载失败后的回滚：从刚失败的模块开始，按加载逆序调用 {@code onUnload}
     * <p>
     * 回滚期间的卸载异常只记录警告，保证尽可能全部回滚
     * */
    private static void rollback() {
        List<Class<? extends Module>> snapshot = new ArrayList<>(LOADED);
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            Class<? extends Module> moduleClass = snapshot.get(i);
            try {
                invoke(moduleClass, "onUnload");
            } catch (Throwable failure) {
                logger().warning("回滚时模块 " + display(moduleClass) + " 的 onUnload 执行失败，继续回滚：" + failure);
            }
        }
        resetState();
    }

    private static void resetState() {
        LOADED.clear();
        INSTANCES.clear();
        REQUIRED.clear();
        declaredModules = List.of();
    }

    private static String describeClasses(Collection<? extends Class<?>> classes) {
        if (classes.isEmpty()) return "（无）";
        StringBuilder builder = new StringBuilder();
        for (Class<?> element : classes) {
            if (!builder.isEmpty()) builder.append(", ");
            builder.append(display(element));
        }
        return builder.toString();
    }

    private static String display(Class<?> type) {
        String name = type.getCanonicalName();
        return name == null ? type.toString() : name;
    }

    /**
     * 本框架唯一允许使用的日志入口：插件级 {@link java.util.logging.Logger}
     * */
    private static Logger logger() {
        return VoidAirRace.getInstance().getLogger();
    }
}
