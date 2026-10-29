package io.github.hhn756.voidairrace.addons.script.api.event;

import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.core.match.MatchCoordinator;
import io.github.hhn756.voidairrace.service.arena.ArenaManager;
import net.sandius.rembulan.ByteString;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * 实体域 Bukkit 事件 → 本包订阅者的桥接监听器<br>
 * 只观察竞技场世界内的非玩家实体，只在 {@link EventPriority#MONITOR} 观察，
 * 不做取消与改写。死亡事件不派发移除事件，区块卸载不算移除；两条排除规则在
 * 本层落实，脚本侧看到的是清理过的事件流
 * <p>
 * 载荷只有值（UUID、实体键、原因），不暴露实体对象，也不携带竞技场标识
 * */
final class EntityEventBridge implements Listener {
    /** 派发目标 */
    private final @NonNull EventApi api;

    EntityEventBridge(@NonNull EventApi api) {
        this.api = api;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSpawn(@NonNull EntitySpawnEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player) return;
        if (!ArenaManager.getInstance().isArenaWorld(entity.getWorld())) return;
        if (!api.hasSubscribers(ScriptEvent.ENTITY_SPAWN)) return;
        api.dispatch(ScriptEvent.ENTITY_SPAWN, currentMatch(), true, uuid(entity), type(entity));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(@NonNull EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Player) return;
        if (!ArenaManager.getInstance().isArenaWorld(entity.getWorld())) return;
        if (!api.hasSubscribers(ScriptEvent.ENTITY_DEATH)) return;
        Entity killer = event.getDamageSource().getCausingEntity();
        api.dispatch(ScriptEvent.ENTITY_DEATH, currentMatch(), true,
                uuid(entity), type(entity), killer == null ? null : uuid(killer));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(@NonNull EntityRemoveEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player) return;
        if (event.getCause() == EntityRemoveEvent.Cause.DEATH
                || event.getCause() == EntityRemoveEvent.Cause.UNLOAD) {
            return;
        }
        if (!ArenaManager.getInstance().isArenaWorld(entity.getWorld())) return;
        if (!api.hasSubscribers(ScriptEvent.ENTITY_REMOVE)) return;
        api.dispatch(ScriptEvent.ENTITY_REMOVE, currentMatch(), true,
                uuid(entity), type(entity), cause(event.getCause()));
    }

    /** 当前比赛（无比赛时为 {@code null}）：实体事件不属于某一场比赛，只取当前那一场作为上下文 */
    private static @Nullable Match currentMatch() {
        return MatchCoordinator.getInstance().getCurrentMatch();
    }

    /** 实体 UUID → Lua 字符串（事件载荷统一用值） */
    private static @NonNull ByteString uuid(@NonNull Entity entity) {
        return ByteString.of(entity.getUniqueId().toString());
    }

    /** 实体类型 → 实体键字符串 */
    private static @NonNull ByteString type(@NonNull Entity entity) {
        return ByteString.of(entity.getType().getKey().toString());
    }

    /** 移除原因 → 小写下划线字符串 */
    private static @NonNull ByteString cause(EntityRemoveEvent.Cause cause) {
        return ByteString.of(cause.name().toLowerCase(Locale.ROOT));
    }
}
