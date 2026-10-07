package io.github.hhn756.voidairrace.core.addons;

import org.bukkit.NamespacedKey;
import org.jspecify.annotations.NonNull;

/**
 * 记录一个游戏元素标签的注册项<br>
 * 标签是纯数据（一个带元数据的 Id），没有实例化语义——同一标签被所有引用它的元素共享，
 * 语义就是「带此标签的元素具备标签所描述的性质」
 * */
public final class TagEntry implements GameElement {
    /**
     * 构造标签注册项实例
     *
     * @param meta 此标签的元数据（Id 与展示信息）
     * */
    public TagEntry(@NonNull GameElementMeta meta) {
        this.meta = meta;
    }

    private final @NonNull GameElementMeta meta;

    public @NonNull NamespacedKey getKey() {
        return meta.id();
    }

    @Override
    public @NonNull GameElementMeta getElementMeta() {
        return meta;
    }
}
