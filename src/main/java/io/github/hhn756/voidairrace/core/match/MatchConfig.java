package io.github.hhn756.voidairrace.core.match;

import io.github.hhn756.voidairrace.constants.Categories;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.match.componentbase.ConfigurableComp;
import io.github.hhn756.voidairrace.core.match.componentbase.CustomData;
import io.github.hhn756.voidairrace.core.match.componentbase.MatchComp;
import io.github.hhn756.voidairrace.infrastructure.registry.Registry;
import io.github.hhn756.voidairrace.result.OperationResult;
import io.github.hhn756.voidairrace.result.ValueResult;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 比赛的配置，决定比赛和其中组件的行为<br>
 * 每个实例都包含一套完整的数据，决定比赛有什么行为（加载什么组件）、接收哪些参数/配置（{@link MatchConfig#create(CustomData...)}的参数）
 * <p>
 * 使用方法：通过{@link MatchConfig#create(CustomData...)}创建配置实例，然后传入{@link Match#create(MatchConfig)}
 */
public class MatchConfig {
    /**
     * 记录此配置所有组件
     * */
    private final @NonNull Map<Class<? extends MatchComp>, MatchComp> components = new HashMap<>();

    /**
     * 记录所有组件添加的自定义数据。值为组件添加的自定义数据，键为添加对应数据的组件
     * */
    private final @NonNull Map<DataKey<?>, CustomData> customData = new HashMap<>();

    /**
     * 标记配置实例是否已被某局比赛使用过，防止重复使用配置实例
     * */
    private boolean used = false;

    /**
     * 创建一个新比赛配置实例
     *
     * @param expectationsConfigs 传递给各组件的用于表示调用者预期参数内容的对象
     *
     * @return 创建的配置实例（成功时携带），失败时携带初始化失败的原因
     * */
    public static @NonNull ValueResult<MatchConfig> create(@Nullable CustomData... expectationsConfigs) {
        MatchConfig newConfig = new MatchConfig();
        return newConfig.init(ConfigDataSource.COMMON, expectationsConfigs)
                .attachValue(newConfig)
                .causedBy(
                        TranslateKeys.Match.MATCH_CONFIG_CREATE_CONFIG_FAILURE_SPECIFIED_CAUSE,
                        TranslateKeys.Match.MATCH_CONFIG_CREATE_CONFIG_FAILURE_UNKNOWN_CAUSE
                );
    }

    /**
     * 根据当前系统状态创建一个比赛配置实例
     *
     * @return 创建的配置实例（成功时携带），失败时携带初始化失败的原因
     * */
    public static @NonNull ValueResult<MatchConfig> createDefault() {
        MatchConfig newConfig = new MatchConfig();
        return newConfig.init(ConfigDataSource.DEFAULT)
                .attachValue(newConfig)
                .causedBy(
                        TranslateKeys.Match.MATCH_CONFIG_CREATE_DEFAULT_CONFIG_FAILURE_SPECIFIED_CAUSE,
                        TranslateKeys.Match.MATCH_CONFIG_CREATE_DEFAULT_CONFIG_FAILURE_UNKNOWN_CAUSE
                );
    }

    /**
     * 只允许通过{@link MatchConfig#create(CustomData...)}和{@link MatchConfig#createDefault()}实例化
     * */
    private MatchConfig() {}

    /**
     * 初始化比赛配置实例<br>
     * 使用独立初始化方法以传递失败信号和消息
     *
     * @param dataSource 配置对象中的自定义数据通过调用组件的指定数据源方法获得
     * @param expectationsConfigs 传递给各组件的用于表示调用者预期参数内容的对象
     * */
    private @NonNull OperationResult init(
            @NonNull ConfigDataSource dataSource,
            @Nullable CustomData... expectationsConfigs
    ) {
        Registry registry = Registry.getInstance();

        // 获取要加载的所有组件
        Collection<CompEntry> compEntries = registry.category(Categories.COMPONENT).list();

        // 加载组件本身
        for (CompEntry compEntry : compEntries) {
            // 实例化
            ValueResult<MatchComp> instantiateResult = compEntry.newInstance();
            if (!instantiateResult.isSuccess()) {
                return instantiateResult.expectValue(
                        TranslateKeys.Match.MATCH_CONFIG_INIT_FAILURE_SPECIFIED_CAUSE,
                        TranslateKeys.Match.MATCH_CONFIG_INIT_FAILURE_UNKNOWN_CAUSE
                );
            }

            // 记录（newInstance 的 WithValue 分支必然携带组件实例，value() 此处不为 null）
            components.put(compEntry.getCompType(), instantiateResult.value());
        }

        // 加载组件的自定义配置数据
        return loadCustomConfig(dataSource, expectationsConfigs);
    }

    /**
     * 加载配置中所有组件的自定义配置数据
     *
     * @param expectationsConfigs 给所有组件的预期配置，允许组件收到{@code null}值（注意不是参数本身为{@code null}）
     * */
    private @NonNull OperationResult loadCustomConfig(
            @NonNull ConfigDataSource dataSource,
            @Nullable CustomData... expectationsConfigs
    ) {
        // 获取所有 ConfigurableComponent 并按优先级排序
        List<ConfigurableComp<?, ?>> configurableComponents = components.values().stream()
                .filter(ConfigurableComp.class::isInstance)
                .map(c -> (ConfigurableComp<?, ?>) c)
                .sorted((a, b) -> b.getConfigPriority() - a.getConfigPriority())
                .collect(Collectors.toList());

        Map<@NonNull Class<? extends MatchComp>, CustomData> expectationsConfigMap = null;
        if (dataSource == ConfigDataSource.COMMON) {
            // 构建期望配置映射
            expectationsConfigMap = new HashMap<>();
            for (CustomData expectationsConfig : expectationsConfigs) {
                expectationsConfigMap.put(expectationsConfig.source(), expectationsConfig);
            }
        }

        // 逐个处理，利用辅助方法捕获通配符
        for (ConfigurableComp<?, ?> component : configurableComponents) {
            OperationResult result = loadSingleCustomConfig(dataSource, component, expectationsConfigMap);
            if (!result.isSuccess()) {
                return result;
            }
        }

        return OperationResult.success();
    }

    private <E extends CustomData, C extends CustomData>
    @NonNull OperationResult loadSingleCustomConfig(
            @NonNull ConfigDataSource dataSource,
            @NonNull ConfigurableComp<E, C> component,
            @NonNull Map<Class<? extends MatchComp>, CustomData> expectationsMap
    ) {
        CustomData configValue = null;
        if (dataSource == ConfigDataSource.COMMON) {
            // 获取期望配置（根据组件类型）
            @SuppressWarnings("unchecked")
            E expected = (E) expectationsMap.get(component.getClass()); // 唯一的调用者在此分支的条件成立时不会传递null

            // 创建自定义配置。
            // 组件实现了本接口即承诺会提供配置数据，因此Empty（成功但没给值）也按失败处理
            ValueResult<C> customConfigResult = component.createCustomConfig(expected);
            if (!customConfigResult.hasValue()) {
                return customConfigResult.expectValue(
                        TranslateKeys.Match.MATCH_CONFIG_LOAD_CUSTOM_CONFIG_FAILURE_SPECIFIED_CAUSE,
                        TranslateKeys.Match.MATCH_CONFIG_LOAD_CUSTOM_CONFIG_FAILURE_UNKNOWN_CAUSE
                );
            }
            configValue = customConfigResult.value();
        } else if (dataSource == ConfigDataSource.DEFAULT) {
            ValueResult<C> defaultConfigResult = component.createDefaultConfig();
            if (!defaultConfigResult.hasValue()) {
                return defaultConfigResult.expectValue(
                        TranslateKeys.Match.MATCH_CONFIG_LOAD_DEFAULT_CONFIG_FAILURE_SPECIFIED_CAUSE,
                        TranslateKeys.Match.MATCH_CONFIG_LOAD_DEFAULT_CONFIG_FAILURE_UNKNOWN_CAUSE
                );
            }
            configValue = defaultConfigResult.value();
        }

        // 存储配置，类型安全
        customData.put(component.getConfigKey(), configValue);
        return OperationResult.success();
    }

    /**
     * 获取指定组件添加的自定义配置数据
     *
     * @param component 指定组件
     *
     * @return 如果指定组件添加了自定义配置数据将返回它添加的数据，否则返回{@code null}
     * */
    public <K extends CustomData> K dataOf(ConfigurableComp<?, K> component) {
        return dataOf(component.getConfigKey());
    }

    /**
     * 获取指定自定义配置数据
     *
     * @param key 数据对应的键
     *
     * @return 如果包含与键对应的数据将返回它，否则返回{@code null}
     * */
    @SuppressWarnings("unchecked")
    public @Nullable <K extends CustomData> K dataOf(DataKey<K> key) {
        return (K) customData.get(key);
    }

    /**
     * 将配置实例标记为已使用过，防止重复使用配置实例
     * */
    public void use() {
        used = true;
    }

    /**
     * 查询配置实例是否已经被某场比赛使用过，防止重复使用配置实例
     *
     * @return 如果配置实例已被使用过将返回 {@code true}，否则返回{@code false}
     * */
    public boolean isUsed() {
        return used;
    }

    /**
     * 获取此配置中指定比赛组件实例
     *
     * @return 指定类型的组件实例
     * */
    @SuppressWarnings("unchecked")
    public @NonNull <C extends MatchComp> C getComp(@NonNull Class<C> componentClass) {
        return (C) components.get(componentClass);
    }

    /**
     * @return 此配置中的所有组件。值为一个组件实例，键为对应组件的类型
     * */
    public @NonNull Map<Class<? extends MatchComp>, MatchComp> getAllComponents() {
        return Collections.unmodifiableMap(components);
    }

    /**
     * 初始化配置实例时如何获取自定义配置项
     * */
    private enum ConfigDataSource {
        /**
         * 调用组件的 {@link ConfigurableComp#createCustomConfig(CustomData)} 获取自定义配置值
         * */
        COMMON,

        /**
         * 调用组件的 {@link ConfigurableComp#createDefaultConfig()} 获取自定义配置值
         * */
        DEFAULT
    }
}
