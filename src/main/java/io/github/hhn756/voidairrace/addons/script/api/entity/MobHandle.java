package io.github.hhn756.voidairrace.addons.script.api.entity;

import io.github.hhn756.voidairrace.addons.script.api.arena.ArenaHandle;
import io.github.hhn756.voidairrace.infrastructure.util.lua.ApiArgs;
import net.sandius.rembulan.LuaRuntimeException;
import net.sandius.rembulan.impl.NonsuspendableFunctionException;
import net.sandius.rembulan.runtime.AbstractFunction0;
import net.sandius.rembulan.runtime.AbstractFunction1;
import net.sandius.rembulan.runtime.ExecutionContext;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * 生物句柄：在活体函数上追加 AI 与幼年状态；敌对生物与动物的句柄类继承本类<br>
 * 本类不持有实例字段，生物状态每次经 {@link #requireEntity} 重取
 * */
public class MobHandle extends LivingEntityHandle {
    MobHandle(
            @NonNull ArenaHandle arena,
            @NonNull UUID uuid,
            @NonNull EntityType type
    ) {
        super(arena, uuid, type);
        addFunction("ai", new Ai(this));
        addFunction("set_ai", new SetAi(this));
        addFunction("baby", new Baby(this));
        addFunction("set_baby", new SetBaby(this));
    }

    /**
     * {@code entity.ai()}：是否启用 AI
     * */
    private static final class Ai extends AbstractFunction0 {
        private final @NonNull MobHandle handle;

        Ai(@NonNull MobHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            context.getReturnBuffer().setTo(handle.requireTyped(Mob.class).isAware());
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code entity.set_ai(b)}：设置是否启用 AI
     * */
    private static final class SetAi extends AbstractFunction1 {
        private final @NonNull MobHandle handle;

        SetAi(@NonNull MobHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            handle.requireMutation();
            boolean ai = ApiArgs.expectBoolean(arg1, "entity.set_ai：第 1 个参数（ai）");
            handle.requireTyped(Mob.class).setAware(ai);
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code entity.baby()}：是否幼年；目标不支持成长时抛错
     * */
    private static final class Baby extends AbstractFunction0 {
        private final @NonNull MobHandle handle;

        Baby(@NonNull MobHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            Ageable ageable = requireAgeable(handle);
            context.getReturnBuffer().setTo(!ageable.isAdult());
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code entity.set_baby(b)}：设置是否幼年；目标不支持成长时抛错
     * */
    private static final class SetBaby extends AbstractFunction1 {
        private final @NonNull MobHandle handle;

        SetBaby(@NonNull MobHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            handle.requireMutation();
            boolean baby = ApiArgs.expectBoolean(arg1, "entity.set_baby：第 1 个参数（baby）");
            Ageable ageable = requireAgeable(handle);
            if (baby) {
                ageable.setBaby();
            } else {
                ageable.setAdult();
            }
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /** 解析幼年状态的目标：当前实体不是可成长实体即抛错 */
    private static @NonNull Ageable requireAgeable(@NonNull MobHandle handle) {
        var entity = handle.requireEntity();
        if (entity instanceof Ageable ageable) return ageable;
        throw new LuaRuntimeException("该生物不支持成长：" + entity.getType().getKey());
    }
}
