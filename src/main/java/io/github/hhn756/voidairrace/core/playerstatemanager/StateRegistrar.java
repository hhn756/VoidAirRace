package io.github.hhn756.voidairrace.core.playerstatemanager;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.constants.Categories;
import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import io.github.hhn756.voidairrace.infrastructure.registry.DefaultSubtable;
import io.github.hhn756.voidairrace.infrastructure.registry.Registry;
import io.github.hhn756.voidairrace.infrastructure.util.ClassScanner;
import io.github.hhn756.voidairrace.result.OperationResult;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 负责创建{@link io.github.hhn756.voidairrace.constants.Categories#STATESYSTEM}注册项类别、扫描并注册插件中所有玩家状态
 * */
public class StateRegistrar implements Module {
    private static StateRegistrar instance;

    public static StateRegistrar getInstance() {
        if (instance == null) throw new NullPointerException("玩家状态注册表实例不存在");
        return instance;
    }

    // ------

    StateRegistrar() {}

    @Override
    public Collection<Class<? extends Module>> getRequiredModules() {
        return List.of(Registry.class);
    }

    /**
     * 插件启用时执行<br>
     * 扫描注册等有副作用的工作必须在此完成：Modules 会先实例化全部模块，再按拓扑顺序加载
     * */
    private void onLoad() {
        registerSystemsAndStates();
        instance = this;
    }

    /** 插件禁用时执行 */
    private void onUnload() {
        instance = null;
    }

    /**
     * 扫描并注册所有状态体系和状态类（实现 {@link PlayerState} 的类）
     */
    private void registerSystemsAndStates() {
        VoidAirRace mainClass = VoidAirRace.getInstance();
        DefaultSubtable<StateSystemEntry, String> stateSystems = Registry.getInstance()
                .createCategory(Categories.STATESYSTEM, StateSystemEntry::getSystemId);

        List<PlayerState> states = instantiateStates();
        registerDefaultSystems(states, stateSystems);
        registerStates(states, stateSystems, mainClass);
    }

    private List<PlayerState> instantiateStates() {
        List<PlayerState> states = new ArrayList<>();
        for (Class<PlayerState> stateClass : ClassScanner.scanSubclasses(
                PlayerState.class,
                "io.github.hhn756.voidairrace.core.playerstatemanager.systems")) {
            try {
                states.add(stateClass.getConstructor().newInstance());
            } catch (ReflectiveOperationException | ExceptionInInitializerError | SecurityException e) {
                throw new IllegalStateException(
                        "实例化玩家状态 \"" + stateClass.getName() + "\" 时发生了异常。这可能是开发者的疏忽", e);
            }
        }
        return states;
    }

    private void registerDefaultSystems(
            List<PlayerState> states,
            DefaultSubtable<StateSystemEntry, String> stateSystems) {
        for (PlayerState state : states) {
            if (!(state instanceof DefaultState)) continue;

            NamespacedKey stateId = state.getId();
            StateSystemEntry entry = new StateSystemEntry(stateId.getNamespace(), stateId);
            String entryId = entry.getSystemId();

            OperationResult addResult = stateSystems.add(entry);
            if (!addResult.isSuccess()) {
                throw new IllegalStateException(
                        "注册玩家状态体系时在 '" + entryId + "' 体系发现重复的默认状态。这可能是开发者的疏忽");
            }
        }
    }

    private void registerStates(
            List<PlayerState> states,
            DefaultSubtable<StateSystemEntry, String> stateSystems,
            VoidAirRace mainClass) {
        for (PlayerState state : states) {
            NamespacedKey stateId = state.getId();
            StateSystemEntry system = stateSystems.get(stateId.getNamespace());

            if (system == null) {
                throw new IllegalStateException(
                        "注册玩家状态时发现状态体系 '" + stateId.getNamespace() + "' 没有默认状态。这可能是开发者的疏忽");
            }
            OperationResult addResult = system.add(state);
            if (!addResult.isSuccess()) {
                throw new IllegalStateException(
                        "注册玩家状态时发现有多个状态的id相同：'" + stateId + "'。这可能是开发者的疏忽");
            }

            if (state instanceof Listener listener) {
                Bukkit.getPluginManager().registerEvents(listener, mainClass);
            }
        }
    }

//    /**
//     * 获取所有状态体系<br>
//     * 键为状态体系名称，值状态体系的元数据
//     * */
//    public HashMap<String, StateSystemMeta> getAllSystems() {
//        return new HashMap<>(nameToSystemMeta);
//    }

//    /**
//     * 获取 id 为 {@code id} 的玩家状态实例
//     *
//     * @param id 指定状态 id
//     *
//     * @return 存在对应id的状态时返回状态实例，否则返回{@code null}
//     * */
//    public PlayerState getStateInstance(NamespacedKey id) {
//        return idToStateInstance.get(id);
//    }
}
