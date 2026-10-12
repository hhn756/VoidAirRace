package io.github.hhn756.voidairrace.result;

import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/**
 * 批量操作的逐项结果：批量操作（如用户包的批量加载/卸载）逐项返回的结果类型，
 * 在{@link OperationResult}的成败语义之上，为每个条目携带定位载荷（如包根、包Id），
 * 使调用方能逐条报告“哪个对象、怎么结束的”
 * <p>
 * 本接口是{@link OperationResult}封闭体系的<b>非封闭分支</b>：具体的条目类型由各批量操作
 * 自行定义（通常是携带载荷的 record），在任意包中实现本接口
 * <p>
 * 实现要求：三个协议方法在本接口被重声明为抽象，实现者必须自行给出实现——
 * 父接口的默认实现按 {@code instanceof Ok/Failed} 判定结局，对携带载荷的条目类型恒不成立
 * */
public non-sealed interface ItemResult extends OperationResult {

    /**
     * 操作是否成功（成功语义由条目类型按自身载荷定义）
     * */
    @Override
    boolean isSuccess();

    /**
     * 获取本结果的用户消息，供最终面向玩家的调用方使用<br>
     * 成功时返回{@code null}
     * */
    @Override
    @Nullable Component message();

    /**
     * 获取本结果的技术性消息，供日志与控制台使用。可包含服务端参数，禁止显示给玩家<br>
     * 成功时返回{@code null}
     * */
    @Override
    @Nullable String techMessage();
}
