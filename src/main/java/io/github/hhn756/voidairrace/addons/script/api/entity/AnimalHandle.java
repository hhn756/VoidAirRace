package io.github.hhn756.voidairrace.addons.script.api.entity;

import io.github.hhn756.voidairrace.addons.script.api.arena.ArenaHandle;
import org.bukkit.entity.EntityType;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * 动物句柄：生物函数之上的类型节点，当前不追加函数<br>
 * 判定见 {@link EntityHandleResolver}：实体类实现 {@code Animals} 时选用本类
 * */
public final class AnimalHandle extends MobHandle {
    AnimalHandle(
            @NonNull ArenaHandle arena,
            @NonNull UUID uuid,
            @NonNull EntityType type
    ) {
        super(arena, uuid, type);
    }
}
