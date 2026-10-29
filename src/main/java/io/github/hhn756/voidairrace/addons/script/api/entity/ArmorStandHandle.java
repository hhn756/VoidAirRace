package io.github.hhn756.voidairrace.addons.script.api.entity;

import io.github.hhn756.voidairrace.addons.script.api.arena.ArenaHandle;
import io.github.hhn756.voidairrace.infrastructure.util.lua.ApiArgs;
import net.sandius.rembulan.impl.NonsuspendableFunctionException;
import net.sandius.rembulan.runtime.AbstractFunction0;
import net.sandius.rembulan.runtime.AbstractFunction1;
import net.sandius.rembulan.runtime.ExecutionContext;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 盔甲架句柄：在活体函数上追加手臂、体型、标记模式与底座<br>
 * 本类不持有实例字段，盔甲架状态每次经 {@link #requireEntity} 重取
 * */
public final class ArmorStandHandle extends LivingEntityHandle {
    ArmorStandHandle(
            @NonNull ArenaHandle arena,
            @NonNull UUID uuid,
            @NonNull EntityType type
    ) {
        super(arena, uuid, type);
        addFunction("arms", new ReadFlag(this, "arms", ArmorStand::hasArms));
        addFunction("set_arms", new WriteFlag(this, "set_arms", "arms", ArmorStand::setArms));
        addFunction("small", new ReadFlag(this, "small", ArmorStand::isSmall));
        addFunction("set_small", new WriteFlag(this, "set_small", "small", ArmorStand::setSmall));
        addFunction("marker", new ReadFlag(this, "marker", ArmorStand::isMarker));
        addFunction("set_marker", new WriteFlag(this, "set_marker", "marker", ArmorStand::setMarker));
        addFunction("base_plate", new ReadFlag(this, "base_plate", ArmorStand::hasBasePlate));
        addFunction("set_base_plate",
                new WriteFlag(this, "set_base_plate", "base_plate", ArmorStand::setBasePlate));
    }

    /**
     * {@code entity.<name>()}：读取盔甲架的一项布尔状态
     * */
    private static final class ReadFlag extends AbstractFunction0 {
        private final @NonNull ArmorStandHandle handle;
        private final @NonNull Function<@NonNull ArmorStand, @NonNull Boolean> reader;

        ReadFlag(
                @NonNull ArmorStandHandle handle,
                @NonNull String name,
                @NonNull Function<@NonNull ArmorStand, @NonNull Boolean> reader
        ) {
            this.handle = handle;
            this.reader = reader;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            context.getReturnBuffer().setTo(
                    reader.apply(handle.requireTyped(ArmorStand.class)));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code entity.<name>(b)}：设置盔甲架的一项布尔状态；要求调用方是竞技场借入者
     * */
    private static final class WriteFlag extends AbstractFunction1 {
        private final @NonNull ArmorStandHandle handle;
        private final @NonNull String functionName;
        private final @NonNull String argumentName;
        private final @NonNull BiConsumer<@NonNull ArmorStand, @NonNull Boolean> writer;

        WriteFlag(
                @NonNull ArmorStandHandle handle,
                @NonNull String functionName,
                @NonNull String argumentName,
                @NonNull BiConsumer<@NonNull ArmorStand, @NonNull Boolean> writer
        ) {
            this.handle = handle;
            this.functionName = functionName;
            this.argumentName = argumentName;
            this.writer = writer;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            handle.requireMutation();
            boolean value = ApiArgs.expectBoolean(arg1,
                    "entity." + functionName + "：第 1 个参数（" + argumentName + "）");
            writer.accept(handle.requireTyped(ArmorStand.class), value);
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }
}
