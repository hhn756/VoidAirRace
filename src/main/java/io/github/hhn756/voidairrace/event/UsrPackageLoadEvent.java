package io.github.hhn756.voidairrace.event;

import io.github.hhn756.voidairrace.addons.usrpackage.UsrPackage;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NonNull;

/**
 * 用户包加载时触发
 * */
public class UsrPackageLoadEvent extends Event {
    /** Bukkit 事件处理器列表 */
    private static final HandlerList HANDLERS = new HandlerList();

    private final UsrPackage newPackage;

    /**
     * @param newPackage 刚刚加载的用户包
     * */
    public UsrPackageLoadEvent(UsrPackage newPackage) {
        this.newPackage = newPackage;
    }

    /**
     * @return 刚刚加载的用户包
     * */
    public @NonNull UsrPackage getPackage() {
        return newPackage;
    }

    /**
     * 获取 Bukkit 事件处理器列表（静态方法）
     *
     * @return 处理器列表
     */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public @NonNull HandlerList getHandlers() {
        return HANDLERS;
    }
}
