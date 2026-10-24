package io.github.hhn756.voidairrace.infrastructure.util.schedulingutil;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.NonNull;

import java.util.Collection;
import java.util.List;

/**
 * Bukkit 调度器的简单包装
 * */
public class SchedulingUtil implements Module {
    private SchedulingUtil() {}

    private static @NonNull JavaPlugin mainClass;
    private static @NonNull BukkitScheduler bukkitScheduler;

    @Override
    public @NonNull Collection<@NonNull Class<? extends Module>> getRequiredModules() {
        return List.of();
    }

    /** 插件启用时执行 */
    private void onLoad() {
        mainClass = VoidAirRace.getInstance();
        bukkitScheduler = Bukkit.getScheduler();
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
    public static @NonNull BukkitTask runOnMainThread(@NonNull Runnable runnable) {
        return bukkitScheduler.runTask(mainClass, runnable);
    }

    /**
     * 在主线程延迟执行一个任务
     *
     * @param runnable 要执行的任务
     * @param delayTicks 延迟的 tick 数
     *
     * @return Bukkit 任务
     *
     * @see BukkitTask
     * */
    public static @NonNull BukkitTask runOnMainThreadLater(@NonNull Runnable runnable, long delayTicks) {
        return bukkitScheduler.runTaskLater(mainClass, runnable, delayTicks);
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
    public static @NonNull BukkitTask runTaskAsync(@NonNull Runnable runnable) {
        return bukkitScheduler.runTaskAsynchronously(mainClass, runnable);
    }
}
