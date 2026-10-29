package io.github.hhn756.voidairrace.addons.script.api.entity;

import io.github.hhn756.voidairrace.addons.script.api.arena.ArenaHandle;
import io.github.hhn756.voidairrace.addons.script.simplevalue.ItemStackValue;
import net.sandius.rembulan.impl.NonsuspendableFunctionException;
import net.sandius.rembulan.runtime.AbstractFunction0;
import net.sandius.rembulan.runtime.AbstractFunction1;
import net.sandius.rembulan.runtime.ExecutionContext;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * 掉落物句柄：在根类通用函数上追加内容读写<br>
 * 本类不持有实例字段，物品堆每次经 {@link #requireEntity} 重取
 * */
public final class ItemHandle extends EntityHandle {
    ItemHandle(
            @NonNull ArenaHandle arena,
            @NonNull UUID uuid,
            @NonNull EntityType type
    ) {
        super(arena, uuid, type);
        addFunction("item", new ItemOf(this));
        addFunction("set_item", new SetItem(this));
    }

    /**
     * {@code entity.item()}：返回掉落物内容，形状为物品堆描述符 {@code {item =, count =}}
     * */
    private static final class ItemOf extends AbstractFunction0 {
        private final @NonNull ItemHandle handle;

        ItemOf(@NonNull ItemHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            Item item = handle.requireTyped(Item.class);
            context.getReturnBuffer().setTo(ItemStackValue.toLua(
                    handle.stateContext(), item.getItemStack()));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code entity.set_item(物品堆描述符)}：替换掉落物内容；要求调用方是竞技场借入者
     * */
    private static final class SetItem extends AbstractFunction1 {
        private final @NonNull ItemHandle handle;

        SetItem(@NonNull ItemHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            handle.requireMutation();
            var stack = ItemStackValue.expect(
                    arg1, "entity.set_item：第 1 个参数（物品堆描述符）");
            handle.requireTyped(Item.class).setItemStack(stack);
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }
}
