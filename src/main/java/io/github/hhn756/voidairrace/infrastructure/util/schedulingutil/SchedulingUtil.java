package io.github.hhn756.voidairrace.infrastructure.util.schedulingutil;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.List;

/**
 * Bukkit 调度器的简单包装
 * */
public class SchedulingUtil implements Module {
    private SchedulingUtil() {}

    private static JavaPlugin mainClass;
    private static BukkitScheduler bukkitScheduler;

    @Override
    public Collection<Class<? extends Module>> getRequiredModules() {
        return List.of();
    }

    /** 插件启用时执行 */
    private void onLoad() {
        mainClass = VoidAirRace.getInstance();
        bukkitScheduler = Bukkit.getScheduler();
    }

    /** 插件停用时执行 */
    private void onUnload() {
    }

    /**
     * 在主线程执行一个任务
     *
     * @param runnable 要执行的任务
     *
     * @return Bukkit 任务
     *
     * @see BukkitTask
     * */
    public static BukkitTask runOnMainThread(Runnable runnable) {
        return bukkitScheduler.runTask(mainClass, runnable);
    }

    /**
     * 异步执行一个任务
     *
     * @param runnable 要执行的任务
     *
     * @return Bukkit 任务
     *
     * @see BukkitTask
     * */
    public static BukkitTask runTaskAsync(Runnable runnable) {
        return bukkitScheduler.runTaskAsynchronously(mainClass, runnable);
    }
}
