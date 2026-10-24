package io.github.hhn756.voidairrace.core.playerstatemanager.systems;

/**
 * 记录所有状态体系的名称
 * */
public enum StateSystem {
    PLAY("play");

    private final String name;

    StateSystem(String name) {
        this.name = name;
    }

    /**
     * 获取字符串形式的状态体系名称
     * */
    public String getName() {
        return name;
    }
}
