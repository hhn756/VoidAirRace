package io.github.hhn756.voidairrace.addons.script.api.block;

import io.github.hhn756.voidairrace.addons.script.simplevalue.BlockDescriptor;
import io.github.hhn756.voidairrace.addons.script.simplevalue.BlockDescriptor.BlockEntityData;
import net.kyori.adventure.text.Component;
import net.sandius.rembulan.LuaRuntimeException;
import org.bukkit.Nameable;
import org.bukkit.block.*;
import org.bukkit.block.sign.Side;
import org.bukkit.inventory.Inventory;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * 把方块描述符写入世界中的某一格<br>
 * 先放置方块数据（类型与状态），再按方块实体数据逐项检查目标能力并写入；
 * 数据字段与方块能力不匹配时抛 Lua 错误。检查发生在放置之后，
 * 因此出错时方块已按描述符的类型与状态放置，仅实体数据未写全
 * */
public final class BlockPlacer {
    /** 静态工具类，不可实例化 */
    private BlockPlacer() {}

    /**
     * 把方块描述符写入指定方块
     *
     * @param block      目标方块
     * @param descriptor 方块描述符
     * @param physics    是否触发方块更新
     * @param what       调用点说明，用于错误消息
     * */
    public static void place(
            @NonNull Block block,
            @NonNull BlockDescriptor descriptor,
            boolean physics,
            @NonNull String what
    ) {
        block.setBlockData(descriptor.blockData(), physics);
        BlockEntityData data = descriptor.entityData();
        if (data == null || data.isEmpty()) return;

        BlockState state = block.getState();
        boolean changed = false;

        if (data.name() != null) {
            if (!(state instanceof Nameable nameable)) {
                throw new LuaRuntimeException(what + "：该方块不支持自定义名称（name）");
            }
            nameable.customName(data.name());
            changed = true;
        }

        if (data.items() != null) {
            if (!(state instanceof Container container)) {
                throw new LuaRuntimeException(what + "：该方块不是容器，不能设置 items");
            }
            Inventory inventory = container.getInventory();
            if (data.items().size() > inventory.getSize()) {
                throw new LuaRuntimeException(what + "：items 有 " + data.items().size()
                        + " 项，超过该容器的槽位数 " + inventory.getSize());
            }
            for (int slot = 0; slot < data.items().size(); slot++) {
                inventory.setItem(slot, data.items().get(slot));
            }
            changed = true;
        }

        if (data.signFront() != null || data.signBack() != null) {
            if (!(state instanceof Sign sign)) {
                throw new LuaRuntimeException(what + "：该方块不是告示牌，不能设置 sign");
            }
            if (data.signFront() != null) writeSignSide(sign, Side.FRONT, data.signFront());
            if (data.signBack() != null) writeSignSide(sign, Side.BACK, data.signBack());
            changed = true;
        }

        if (data.command() != null) {
            if (!(state instanceof CommandBlock commandBlock)) {
                throw new LuaRuntimeException(what + "：该方块不是命令方块，不能设置 command");
            }
            commandBlock.setCommand(data.command());
            changed = true;
        }

        if (data.spawnerEntity() != null || data.spawnerDelay() != null) {
            if (!(state instanceof CreatureSpawner spawner)) {
                throw new LuaRuntimeException(what + "：该方块不是刷怪笼，不能设置 spawner");
            }
            if (data.spawnerEntity() != null) spawner.setSpawnedType(data.spawnerEntity());
            if (data.spawnerDelay() != null) spawner.setDelay(data.spawnerDelay());
            changed = true;
        }

        // 实体数据本身不参与方块更新，写入后统一落盘
        if (changed) state.update(false);
    }

    /** 告示牌单面按行写入，行号从 0 起 */
    private static void writeSignSide(
            @NonNull Sign sign, @NonNull Side side, @NonNull List<@NonNull Component> lines) {
        var target = sign.getSide(side);
        for (int line = 0; line < lines.size(); line++) {
            target.line(line, lines.get(line));
        }
    }
}
