package io.github.hhn756.voidairrace.addons.script.api.entity;

import io.github.hhn756.voidairrace.addons.script.api.arena.ArenaHandle;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.impl.NonsuspendableFunctionException;
import net.sandius.rembulan.runtime.AbstractFunction0;
import net.sandius.rembulan.runtime.ExecutionContext;
import org.bukkit.Registry;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Painting;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/**
 * 画句柄：在根类通用函数上追加画作与附着方向读取<br>
 * 本类不持有实例字段，画状态每次经 {@link #requireEntity} 重取
 * */
public final class PaintingHandle extends EntityHandle {
    PaintingHandle(
            @NonNull ArenaHandle arena,
            @NonNull UUID uuid,
            @NonNull EntityType type
    ) {
        super(arena, uuid, type);
        addFunction("art", new ArtOf(this));
        addFunction("facing", new Facing(this));
    }

    /**
     * {@code entity.art()}：返回画作键字符串，如 {@code "minecraft:kebab"}
     * */
    private static final class ArtOf extends AbstractFunction0 {
        private final @NonNull PaintingHandle handle;

        ArtOf(@NonNull PaintingHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            Painting painting = handle.requireTyped(Painting.class);
            context.getReturnBuffer().setTo(ByteString.of(
                    Registry.ART.getKey(painting.getArt()).toString()));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code entity.facing()}：返回附着方向字符串，{@code "north"} / {@code "south"} /
     * {@code "east"} / {@code "west"}
     * */
    private static final class Facing extends AbstractFunction0 {
        private final @NonNull PaintingHandle handle;

        Facing(@NonNull PaintingHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            String facing = handle.requireTyped(Painting.class)
                    .getFacing().name().toLowerCase(Locale.ROOT);
            context.getReturnBuffer().setTo(ByteString.of(facing));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }
}
