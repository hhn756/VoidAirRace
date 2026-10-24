package io.github.hhn756.voidairrace.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NonNull;

/**
 * 用户包脚本运行时状态被作废时触发——包卸载与包加载失败清理都会走到这里<br>
 * 持有包级资源的子系统（脚本 API 的各状态类别等）各自监听本事件清理自己名下的资源；
 * 包 Id 过滤是监听方自己的职责（事件广播给所有监听者）
 * */
public class UsrPackageUnloadEvent extends Event {
    /** Bukkit 事件处理器列表 */
    private static final HandlerList HANDLERS = new HandlerList();

    private final String pkgId;

    /**
     * @param pkgId 被作废脚本运行时状态的包 Id
     * */
    public UsrPackageUnloadEvent(String pkgId) {
        this.pkgId = pkgId;
    }

    /**
     * @return 被作废脚本运行时状态的包 Id
     * */
    public @NonNull String getPkgId() {
        return pkgId;
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
