package io.github.hhn756.voidairrace.addons.script.api.entity;

import io.github.hhn756.voidairrace.addons.script.api.arena.ArenaHandle;
import io.github.hhn756.voidairrace.infrastructure.util.lua.ApiArgs;
import net.sandius.rembulan.LuaRuntimeException;
import net.sandius.rembulan.impl.NonsuspendableFunctionException;
import net.sandius.rembulan.runtime.AbstractFunction0;
import net.sandius.rembulan.runtime.AbstractFunction1;
import net.sandius.rembulan.runtime.ExecutionContext;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * 活体实体句柄：在根类通用函数上追加生命值、击杀与隐身<br>
 * 子类（生物、盔甲架）继续追加各自的函数；本类不持有实例字段，活体状态每次经
 * {@link #requireEntity} 重取
 * */
public class LivingEntityHandle extends EntityHandle {
    LivingEntityHandle(
            @NonNull ArenaHandle arena,
            @NonNull UUID uuid,
            @NonNull EntityType type
    ) {
        super(arena, uuid, type);
        addFunction("health", new Health(this));
        addFunction("set_health", new SetHealth(this));
        addFunction("kill", new Kill(this));
        addFunction("invisible", new Invisible(this));
        addFunction("set_invisible", new SetInvisible(this));
    }

    /**
     * {@code entity.health()}：返回当前生命值
     * */
    private static final class Health extends AbstractFunction0 {
        private final @NonNull LivingEntityHandle handle;

        Health(@NonNull LivingEntityHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            context.getReturnBuffer().setTo(
                    handle.requireTyped(LivingEntity.class).getHealth());
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code entity.set_health(n)}：设置当前生命值；超过上限按上限处理
     * */
    private static final class SetHealth extends AbstractFunction1 {
        private final @NonNull LivingEntityHandle handle;

        SetHealth(@NonNull LivingEntityHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            handle.requireMutation();
            double value = ApiArgs.expectDouble(
                    arg1, "entity.set_health：第 1 个参数（health）");
            if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) {
                throw new LuaRuntimeException(
                        "entity.set_health：生命值必须是非负有限数字，实际：" + value);
            }
            LivingEntity living = handle.requireTyped(LivingEntity.class);
            living.setHealth(Math.min(value, living.getMaxHealth()));
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code entity.kill()}：走正常死亡流程（掉落、死亡事件）；返回调用后是否已进入死亡状态
     * */
    private static final class Kill extends AbstractFunction0 {
        private final @NonNull LivingEntityHandle handle;

        Kill(@NonNull LivingEntityHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            handle.requireMutation();
            LivingEntity living = handle.requireTyped(LivingEntity.class);
            living.damage(Float.MAX_VALUE);
            context.getReturnBuffer().setTo(living.isDead() || !living.isValid());
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code entity.invisible()}：是否隐身
     * */
    private static final class Invisible extends AbstractFunction0 {
        private final @NonNull LivingEntityHandle handle;

        Invisible(@NonNull LivingEntityHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            context.getReturnBuffer().setTo(
                    handle.requireTyped(LivingEntity.class).isInvisible());
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code entity.set_invisible(b)}：设置是否隐身
     * */
    private static final class SetInvisible extends AbstractFunction1 {
        private final @NonNull LivingEntityHandle handle;

        SetInvisible(@NonNull LivingEntityHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            handle.requireMutation();
            boolean invisible = ApiArgs.expectBoolean(
                    arg1, "entity.set_invisible：第 1 个参数（invisible）");
            handle.requireTyped(LivingEntity.class).setInvisible(invisible);
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }
}
