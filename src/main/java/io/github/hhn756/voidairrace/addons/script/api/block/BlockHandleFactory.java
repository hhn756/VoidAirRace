package io.github.hhn756.voidairrace.addons.script.api.block;

import io.github.hhn756.voidairrace.addons.script.api.arena.ArenaHandle;
import io.github.hhn756.voidairrace.addons.script.simplevalue.BlockPos;
import org.bukkit.Material;
import org.jspecify.annotations.NonNull;

/**
 * 方块句柄工厂：精确材质表与方块大类表共用的创建函数<br>
 * 只接收稳定参数，不接收方块实例——句柄不缓存世界状态
 * */
@FunctionalInterface
interface BlockHandleFactory {
    /**
     * 创建方块句柄
     *
     * @param arena    方块所属竞技场的借据句柄
     * @param pos      方块坐标
     * @param material 已校验过的方块材质
     *
     * @return 对应句柄
     * */
    @NonNull BlockHandle create(
            @NonNull ArenaHandle arena,
            @NonNull BlockPos pos,
            @NonNull Material material
    );
}
