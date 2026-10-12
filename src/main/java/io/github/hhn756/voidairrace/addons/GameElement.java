package io.github.hhn756.voidairrace.addons;

import org.jspecify.annotations.NonNull;

/**
 * ...
 * */
public interface GameElement {
    /**
     * 获取此游戏元素的元数据
     * */
    @NonNull GameElementMeta getElementMeta();
}
