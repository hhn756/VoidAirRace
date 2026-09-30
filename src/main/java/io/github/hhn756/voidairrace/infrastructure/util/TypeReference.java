package io.github.hhn756.voidairrace.infrastructure.util;

import org.jspecify.annotations.NonNull;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * 通过匿名子类在运行时捕获泛型实参<br>
 *
 * @param <T> （要）记录的类型
 */
public abstract class TypeReference<T> {
    /**
     * 缓存提取出来的完整泛型类型
     */
    private final @NonNull Type type;

    /**
     * 必须通过 {@code new TypeReference<类型>(){}} 以匿名子类形式实例化
     */
    protected TypeReference() {
        // 获取当前匿名子类的父类（即 TypeReference<T> 的具体参数化类型）
        Type superClass = getClass().getGenericSuperclass();

        if (superClass instanceof ParameterizedType pType) {
            // 取父类泛型参数列表中的第一个参数
            type = pType.getActualTypeArguments()[0];
        } else {
            throw new IllegalArgumentException("必须使用匿名子类并提供泛型实参");
        }
    }

    /**
     * 获取记录的完整类型（可能是参数化类型，如 {@code List<String>}）
     */
    public @NonNull Type getType() {
        return type;
    }

    /**
     * 当记录的类型是简单类时，返回对应的 {@link Class}
     *
     * @throws IllegalStateException 如果类型是参数化类型等非简单类形式
     */
    @SuppressWarnings("unchecked")
    public @NonNull Class<T> getTypeClass() {
        if (type instanceof Class<?> clazz) {
            return (Class<T>) clazz;
        }
        throw new IllegalStateException("类型不是简单的 Class，而是 " + type);
    }
}
