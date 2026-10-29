package io.github.hhn756.voidairrace.addons.script.api.entity;

import io.github.hhn756.voidairrace.addons.script.api.arena.ArenaHandle;
import net.sandius.rembulan.Table;
import org.bukkit.entity.*;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * 实体句柄解析：句柄类只由预期类型决定，不看世界状态<br>
 * 判定顺序：玩家 → 盔甲架（按键精确匹配）→ 实体类实现 {@code Painting} →
 * {@code Item} → {@code Monster} → {@code Animals} → {@code Mob} →
 * {@code LivingEntity} → 根类兜底。除盔甲架外都只看 {@link EntityType#getEntityClass()}，
 * 不实例化实体；实体类型没有实体类时落到根类
 * */
public final class EntityHandleResolver {
    /** 静态工具类，不可实例化 */
    private EntityHandleResolver() {}

    /**
     * 为指定实体创建句柄
     *
     * @param arena 实体所属竞技场的借据句柄
     * @param uuid  实体 UUID
     * @param type  脚本给出的预期类型，决定句柄类与句柄记下的类型断言
     *
     * @return 对应句柄的 Lua 表
     * */
    public static @NonNull Table create(
            @NonNull ArenaHandle arena,
            @NonNull UUID uuid,
            @NonNull EntityType type
    ) {
        if (type == EntityType.PLAYER) return new PlayerHandle(arena, uuid).table();
        if (type == EntityType.ARMOR_STAND) {
            return new ArmorStandHandle(arena, uuid, type).table();
        }

        Class<? extends Entity> clazz = type.getEntityClass();
        if (clazz != null) {
            if (Painting.class.isAssignableFrom(clazz)) return new PaintingHandle(arena, uuid, type).table();
            if (Item.class.isAssignableFrom(clazz)) return new ItemHandle(arena, uuid, type).table();
            if (Monster.class.isAssignableFrom(clazz)) return new MonsterHandle(arena, uuid, type).table();
            if (Animals.class.isAssignableFrom(clazz)) return new AnimalHandle(arena, uuid, type).table();
            if (Mob.class.isAssignableFrom(clazz)) return new MobHandle(arena, uuid, type).table();
            if (LivingEntity.class.isAssignableFrom(clazz)) {
                return new LivingEntityHandle(arena, uuid, type).table();
            }
        }
        return new EntityHandle(arena, uuid, type).table();
    }
}
