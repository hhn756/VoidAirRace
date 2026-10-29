package io.github.hhn756.voidairrace.addons.script.api.block;

import io.github.hhn756.voidairrace.addons.script.api.arena.ArenaHandle;
import io.github.hhn756.voidairrace.addons.script.simplevalue.BlockPos;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.jspecify.annotations.NonNull;

import java.util.EnumMap;
import java.util.Map;

/**
 * 方块句柄解析：按预期类型（方块材质）查精确表 → 方块大类（{@link BlockCategory} 声明顺序）
 * → {@link BlockHandle} 兜底<br>
 * 精确表是例外表，不是全量表：只有确实需要独家句柄类的方块才登记，且登记的类型应继承
 * 其所属大类的句柄类，以便自动带上大类函数
 * */
public final class BlockHandleResolver {
    /** 精确材质覆盖表，默认空 */
    private static final @NonNull Map<@NonNull Material, @NonNull BlockHandleFactory> EXACT =
            new EnumMap<>(Material.class);

    /** 静态工具类，不可实例化 */
    private BlockHandleResolver() {}

    /**
     * 为指定方块创建句柄
     *
     * @param arena            方块所属竞技场的借据句柄
     * @param pos              方块坐标
     * @param expectedMaterial 脚本给出的预期类型（方块材质），决定精确表查表键与句柄记下的材质断言
     * @param block            坐标处的当前方块，仅用于大类判定，不进句柄
     *
     * @return 对应句柄
     * */
    public static @NonNull BlockHandle create(
            @NonNull ArenaHandle arena,
            @NonNull BlockPos pos,
            @NonNull Material expectedMaterial,
            @NonNull Block block
    ) {
        BlockHandleFactory exact = EXACT.get(expectedMaterial);
        if (exact != null) return exact.create(arena, pos, expectedMaterial);

        BlockCategory category = BlockCategory.classify(block);
        if (category != null) return category.factory().create(arena, pos, expectedMaterial);

        return new BlockHandle(arena, pos, expectedMaterial);
    }
}
