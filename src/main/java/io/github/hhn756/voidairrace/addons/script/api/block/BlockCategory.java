package io.github.hhn756.voidairrace.addons.script.api.block;

import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 方块大类：精确材质表未命中时，按枚举声明顺序匹配，第一个命中的类别决定句柄类<br>
 * 类别判定只看方块本身具备的能力，不看材质名；新增类别时在常量里实现
 * {@link #matches(Block)} 并登记工厂，判定顺序即声明顺序
 * */
enum BlockCategory {
    /** 容器方块：箱子、木桶、熔炉、漏斗、发射器等，匹配 {@link Container} 方块状态 */
    CONTAINER(ContainerBlockHandle::new) {
        @Override
        boolean matches(@NonNull Block block) {
            return block.getState() instanceof Container;
        }
    };

    /** 本类别对应的句柄工厂 */
    private final @NonNull BlockHandleFactory factory;

    BlockCategory(@NonNull BlockHandleFactory factory) {
        this.factory = factory;
    }

    /**
     * 判断方块是否属于本类别
     *
     * @param block 目标方块
     *
     * @return 属于时为{@code true}
     * */
    abstract boolean matches(@NonNull Block block);

    /** @return 本类别的句柄工厂 */
    @NonNull BlockHandleFactory factory() {
        return factory;
    }

    /**
     * 按声明顺序匹配方块所属的大类
     *
     * @return 第一个命中的类别；没有命中时为{@code null}
     * */
    static @Nullable BlockCategory classify(@NonNull Block block) {
        for (BlockCategory category : values()) {
            if (category.matches(block)) return category;
        }
        return null;
    }
}
