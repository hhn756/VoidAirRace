package io.github.hhn756.voidairrace.infrastructure.registry;

/**
 * 注册项类别的标识，通过此类实例在注册表中访问对应子表<br>
 * 以静态常量形式集中声明（见{@link io.github.hhn756.voidairrace.constants.Categories}类）
 * <p>
 * 泛型参数在编译期确定该类别的注册项类型、查询键类型和子表实现类型<br>
 * 注册表据此保证类型一致地记录与查询
 *
 * @param <I> 该类别下所有注册项的类型。无约束，任意类型（包括简单值类型）均可作为注册项
 * @param <K> 此类注册项的主键类型
 * @param <C> 此类注册项的子表类型。绑定到{@link DefaultSubtable}本身表示使用默认实现
 *            （定义时须注入键计算函数），绑定到其子类表示该类别使用自定义实现。
 *            两类实现都必须先通过{@link Registry}的createCategory方法定义后才能获取子表
 */
public class CategoryId<I, K, C extends DefaultSubtable<I, K>> {}
