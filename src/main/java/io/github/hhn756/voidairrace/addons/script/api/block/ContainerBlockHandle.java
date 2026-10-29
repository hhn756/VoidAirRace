package io.github.hhn756.voidairrace.addons.script.api.block;

import io.github.hhn756.voidairrace.addons.script.api.arena.ArenaHandle;
import io.github.hhn756.voidairrace.addons.script.simplevalue.BlockPos;
import io.github.hhn756.voidairrace.addons.script.simplevalue.ItemStackValue;
import io.github.hhn756.voidairrace.infrastructure.util.lua.ApiArgs;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.LuaRuntimeException;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.Table;
import net.sandius.rembulan.impl.NonsuspendableFunctionException;
import net.sandius.rembulan.runtime.AbstractFunction0;
import net.sandius.rembulan.runtime.AbstractFunction1;
import net.sandius.rembulan.runtime.AbstractFunction2;
import net.sandius.rembulan.runtime.ExecutionContext;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.Lootable;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * 容器方块句柄：在根类通用函数上追加物品栏与战利品表操作，覆盖箱子、木桶、熔炉、漏斗等<br>
 * 容器判定与工厂登记见{@link BlockCategory}；本类不持有实例字段，容器状态每次经
 * {@link #requireBlock} 重取
 * <p>
 * 槽位对脚本统一按 Lua 习惯 1 起始（第 1 格对应物品栏下标 0）；越界、非整数即抛错。
 * 读函数不需要修改权，写函数要求调用方是本竞技场的借入者
 * */
class ContainerBlockHandle extends BlockHandle {
    ContainerBlockHandle(
            @NonNull ArenaHandle arena,
            @NonNull BlockPos pos,
            @NonNull Material material
    ) {
        super(arena, pos, material);
        addFunction("clear", new Clear(this));
        addFunction("size", new Size(this));
        addFunction("get", new Get(this));
        addFunction("items", new Items(this));
        addFunction("set", new Set(this));
        addFunction("add", new Add(this));
        addFunction("remove", new Remove(this));
        addFunction("loot_table", new LootTableOf(this));
        addFunction("set_loot_table", new SetLootTable(this));
        addFunction("clear_loot_table", new ClearLootTable(this));
    }

    /** 重新解析方块并转为容器状态 */
    private static @NonNull Container containerOf(@NonNull BlockHandle handle) {
        if (handle.requireBlock().getState() instanceof Container container) return container;
        throw new LuaRuntimeException("该位置的方块已不是容器");
    }

    /**
     * 校验脚本给出的 1 起始槽位并转为物品栏下标
     *
     * @param arg  Lua 原值
     * @param what 参数用途说明，用于错误消息
     * @param size 容器格数
     *
     * @return 0 起始的物品栏下标
     * */
    private static int expectSlot(@Nullable Object arg, @NonNull String what, int size) {
        double value = ApiArgs.expectDouble(arg, what);
        if (value != Math.floor(value) || value < 1 || value > size) {
            throw new LuaRuntimeException(what + "超出范围：应为 1 到 " + size + " 的整数，实际：" + value);
        }
        return (int) value - 1;
    }

    /**
     * {@code block.clear()}：清空容器物品栏
     * */
    private static final class Clear extends AbstractFunction0 {
        private final @NonNull BlockHandle handle;

        Clear(@NonNull BlockHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            handle.requireMutation();
            Container container = containerOf(handle);
            container.getInventory().clear();
            container.update();
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code block.size()}：返回容器物品栏格数
     * */
    private static final class Size extends AbstractFunction0 {
        private final @NonNull BlockHandle handle;

        Size(@NonNull BlockHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            context.getReturnBuffer().setTo((long) containerOf(handle).getInventory().getSize());
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code block.get(slot)}：返回指定槽位的物品描述符；空槽返回 {@code nil}
     * */
    private static final class Get extends AbstractFunction1 {
        private final @NonNull BlockHandle handle;

        Get(@NonNull BlockHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            Inventory inventory = containerOf(handle).getInventory();
            int slot = expectSlot(arg1, "block.get：第 1 个参数（槽位）", inventory.getSize());
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) {
                context.getReturnBuffer().setTo();
                return;
            }
            context.getReturnBuffer().setTo(ItemStackValue.toLua(handle.stateContext(), stack));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code block.items()}：返回非空槽位数组，每项为
     * {@code {slot = 槽位（1 起始）, item = 材质键, count = 数量}}，按槽位升序
     * */
    private static final class Items extends AbstractFunction0 {
        private final @NonNull BlockHandle handle;

        Items(@NonNull BlockHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            Inventory inventory = containerOf(handle).getInventory();
            StateContext stateContext = handle.stateContext();
            Table result = stateContext.newTable();
            long index = 1;
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack == null || stack.getType().isAir()) continue;
                Table entry = ItemStackValue.toLua(stateContext, stack);
                entry.rawset(ByteString.of("slot"), (long) (slot + 1));
                result.rawset(index++, entry);
            }
            context.getReturnBuffer().setTo(result);
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code block.set(slot, item)}：把指定槽位设为物品描述符所述物品
     * */
    private static final class Set extends AbstractFunction2 {
        private final @NonNull BlockHandle handle;

        Set(@NonNull BlockHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1, @Nullable Object arg2) {
            handle.requireMutation();
            Container container = containerOf(handle);
            Inventory inventory = container.getInventory();
            int slot = expectSlot(arg1, "block.set：第 1 个参数（槽位）", inventory.getSize());
            ItemStack stack = ItemStackValue.expect(arg2, "block.set：第 2 个参数（物品）");
            inventory.setItem(slot, stack);
            container.update();
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code block.add(item)}：把物品放入第一个能容纳的位置<br>
     * 返回未能放入的数量（0 表示全部放入）；背包满时余量不丢弃
     * */
    private static final class Add extends AbstractFunction1 {
        private final @NonNull BlockHandle handle;

        Add(@NonNull BlockHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            handle.requireMutation();
            ItemStack stack = ItemStackValue.expect(arg1, "block.add：第 1 个参数（物品）");
            Container container = containerOf(handle);
            Map<Integer, ItemStack> leftover = container.getInventory().addItem(stack);
            container.update();
            int remaining = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
            context.getReturnBuffer().setTo((long) remaining);
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code block.remove(slot)}：清空指定槽位
     * */
    private static final class Remove extends AbstractFunction1 {
        private final @NonNull BlockHandle handle;

        Remove(@NonNull BlockHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            handle.requireMutation();
            Container container = containerOf(handle);
            Inventory inventory = container.getInventory();
            int slot = expectSlot(arg1, "block.remove：第 1 个参数（槽位）", inventory.getSize());
            inventory.clear(slot);
            container.update();
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code block.loot_table()}：返回容器绑定的战利品表 Id；未绑定或该容器不支持战利品表时返回 {@code nil}
     * */
    private static final class LootTableOf extends AbstractFunction0 {
        private final @NonNull BlockHandle handle;

        LootTableOf(@NonNull BlockHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            Container container = containerOf(handle);
            if (!(container instanceof Lootable lootable)) {
                context.getReturnBuffer().setTo();
                return;
            }
            LootTable table = lootable.getLootTable();
            if (table == null) {
                context.getReturnBuffer().setTo();
                return;
            }
            context.getReturnBuffer().setTo(ByteString.of(table.getKey().toString()));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code block.set_loot_table(id)}：给容器绑定战利品表<br>
     * {@code id} 为战利品表 Id（{@code "ns:path"}，省略命名空间时按 {@code minecraft}），
     * 表须已存在（插件自带数据包中的表也可引用）；已有物品不会被清空，
     * 重置补给箱应自行先 {@code clear()}。战利品在容器首次被打开时生成
     * */
    private static final class SetLootTable extends AbstractFunction1 {
        private final @NonNull BlockHandle handle;

        SetLootTable(@NonNull BlockHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            handle.requireMutation();
            String spec = ApiArgs.expectString(arg1, "block.set_loot_table：第 1 个参数（战利品表 Id）");
            NamespacedKey key;
            try {
                key = NamespacedKey.fromString(spec);
            } catch (IllegalArgumentException e) {
                throw new LuaRuntimeException("战利品表 Id 非法：" + spec + "（" + e.getMessage() + "）");
            }
            if (key == null) throw new LuaRuntimeException("战利品表 Id 非法：" + spec);
            LootTable table = Bukkit.getLootTable(key);
            if (table == null) throw new LuaRuntimeException("战利品表不存在：" + spec);

            Container container = containerOf(handle);
            if (!(container instanceof Lootable lootable)) {
                throw new LuaRuntimeException("该方块不是支持战利品表的容器");
            }
            lootable.setLootTable(table);
            container.update();
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * {@code block.clear_loot_table()}：解除容器绑定的战利品表
     * */
    private static final class ClearLootTable extends AbstractFunction0 {
        private final @NonNull BlockHandle handle;

        ClearLootTable(@NonNull BlockHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            handle.requireMutation();
            Container container = containerOf(handle);
            if (!(container instanceof Lootable lootable)) {
                throw new LuaRuntimeException("该方块不是支持战利品表的容器");
            }
            lootable.clearLootTable();
            container.update();
            context.getReturnBuffer().setTo();
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }
}
