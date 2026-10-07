package io.github.hhn756.voidairrace.result;

import net.kyori.adventure.text.Component;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 不产出值的操作的结果：一次操作全部结束后，交给调用方描述“这次怎么结束的”的不可变值<br>
 * 只有两种结局：成功（{@link Ok}）与失败（{@link Failed}）。
 * 结局由类型本身表达——没有 success 布尔字段，{@link #isSuccess()}由 {@code instanceof} 派生，
 * 因此“失败却携带矛盾信息”这类非法状态无法构造<br>
 * <p>
 * 失败结果携带两种互斥用途的消息：
 * <ul>
 *     <li><b>用户消息</b>（{@link Failed#reasonKey} + {@link Failed#userDetail}）：
 *     最终显示给玩家的文案，不详细，禁止包含服务端参数（路径、内部 id、异常文本等）；
 *     只能由翻译键和内层用户消息组合而成，文案在客户端渲染；</li>
 *     <li><b>技术性消息</b>（{@link Failed#techDetail} + {@link Failed#cause}）：
 *     仅供日志与控制台，详细，可包含服务端参数。
 *     硬约束：禁止通过任何玩家可见通道（聊天、广播、标题、BossBar 等）发送给玩家，
 *     消费它只能写入日志与控制台。</li>
 * </ul>
 * 两种消息的流通路径彼此独立：用户消息只会由翻译键和内层用户消息组合而来，
 * 服务端参数只能进入技术性消息。
 * <p>
 * 用法约定：
 * <ul>
 *     <li>方法内不把结果转换为显示文本，失败时只携带本层翻译键（见{@link Failed#reasonKey}）
 *     与技术性描述（见{@link Failed#techDetail}）；
 *     中间层向上传播时用{@link #causedBy(String)}（或键对版本{@link #causedBy(String, String)}），
 *     只搬运键与消息，不产生文案；</li>
 *     <li>只有最终面向玩家的调用方调用{@link #message()}把结果转换为用户文案，
 *     面向日志的调用方调用{@link #techMessage()}获取技术性文本。</li>
 * </ul>
 *
 * @see ValueResult 需要携带一个值时使用
 * */
public sealed interface OperationResult permits OperationResult.Ok, OperationResult.Failed {

    /**
     * 操作成功，不携带任何信息
     * */
    record Ok() implements OperationResult {}

    /**
     * 操作失败，携带用户消息（显示给玩家）与技术性消息（仅日志）两种通道
     * <p>
     * 四个字段各属一个通道、各有唯一职责，向上层传播时（见{@link #causedBy(String)}）规则不同：
     * <table border="1">
     *     <tr><th>字段</th><th>通道</th><th>含义</th><th>向上层传播时</th></tr>
     *     <tr><td>{@link #reasonKey}</td><td>用户</td><td>本层失败原因的翻译键</td><td>被外层键覆盖</td></tr>
     *     <tr><td>{@link #userDetail}</td><td>用户</td><td>内层已转换好的用户文案</td><td>变长（作为外层键的参数嵌入）</td></tr>
     *     <tr><td>{@link #techDetail}</td><td>技术性</td><td>本层技术性描述，可含服务端参数</td><td>原样透传</td></tr>
     *     <tr><td>{@link #cause}</td><td>技术性</td><td>最内层异常</td><td>原样透传，不参与包装</td></tr>
     * </table>
     *
     * @param reasonKey  本层失败原因的翻译键（{@link Component#translatable(String)}的键）。
     *                   允许为{@code null}：表示“无可上报原因的失败”，兜底文案由最外层调用方负责
     *                   （旧版允许此类结果，保留兼容）
     * @param userDetail 内层已转换好的用户文案，转换时作为{@code reasonKey}的参数；可为{@code null}。
     *                   禁止包含服务端参数
     * @param techDetail 本层技术性描述（如出错的路径、参数值），仅供日志；可为{@code null}。
     *                   可包含服务端参数，禁止显示给玩家
     * @param cause      导致本次失败的内层异常；可为{@code null}。仅用于日志，不呈现给玩家
     * */
    record Failed(
            @Nullable String reasonKey,
            @Nullable Component userDetail,
            @Nullable String techDetail,
            @Nullable Throwable cause
    ) implements OperationResult {

        /**
         * 把本失败结果转换为用户消息（显示给玩家的文案）
         *
         * @return 转换规则见{@link #composeMessage(String, Component)}
         * */
        public @Nullable Component message() {
            return composeMessage(reasonKey, userDetail);
        }

        /**
         * 把本失败结果转换为技术性消息（写入日志的文本）<br>
         * 只拼接存在的部分：只有描述时返回描述；只有异常时返回异常文本；
         * 两者都有时拼接；两者皆无时返回{@code null}
         * */
        public @Nullable String techMessage() {
            if (techDetail == null) return cause == null ? null : cause.toString();
            return cause == null ? techDetail : techDetail + "（异常：" + cause + "）";
        }
    }

    // ------ 构造 ------

    /**
     * 创建一个表示成功的结果
     */
    static OperationResult success() {
        return new Ok();
    }

    /**
     * 创建一个失败结果（用户消息只有翻译键，无技术性消息）
     *
     * @param reasonKey 本层失败原因的翻译键，可为{@code null}（无可上报原因的失败）
     * */
    static OperationResult failure(@Nullable String reasonKey) {
        return new Failed(reasonKey, null, null, null);
    }

    /**
     * 创建一个失败结果，同时给出两种消息
     *
     * @param reasonKey  本层失败原因的翻译键（用户消息），可为{@code null}
     * @param techDetail 本层技术性描述，可含服务端参数，仅日志；可为{@code null}
     * */
    static OperationResult failure(@Nullable String reasonKey, @Nullable String techDetail) {
        return new Failed(reasonKey, null, techDetail, null);
    }

    /**
     * 创建一个失败结果（全字段版本）
     *
     * @param reasonKey  本层失败原因的翻译键（用户消息），可为{@code null}
     * @param userDetail 内层已转换好的用户文案，转换时作为{@code reasonKey}的参数；可为{@code null}。
     *                   禁止包含服务端参数
     * @param techDetail 本层技术性描述，可为{@code null}。可包含服务端参数，禁止显示给玩家
     * @param cause      内层异常，可为{@code null}
     * */
    static OperationResult failure(
            @Nullable String reasonKey,
            @Nullable Component userDetail,
            @Nullable String techDetail,
            @Nullable Throwable cause
    ) {
        return new Failed(reasonKey, userDetail, techDetail, cause);
    }

    // ------ 查询与传播（本层把内层结果变成自己的结果） ------

    /**
     * 操作是否成功
     *
     * @return 结局为{@link Ok}时返回{@code true}
     */
    default boolean isSuccess() {
        return this instanceof Ok;
    }

    /**
     * 获取本结果的用户消息，供最终面向玩家的调用方使用
     *
     * @return 结局为{@link Failed}时同{@link Failed#message()}；成功时为{@code null}
     * */
    default @Nullable Component message() {
        return this instanceof Failed f ? f.message() : null;
    }

    /**
     * 获取本结果的技术性消息，供日志与控制台使用。可包含服务端参数，禁止显示给玩家
     *
     * @return 结局为{@link Failed}时同{@link Failed#techMessage()}；成功时为{@code null}
     * */
    default @Nullable String techMessage() {
        return this instanceof Failed f ? f.techMessage() : null;
    }

    /**
     * 把内层失败包装成本层失败（中间层向上传播的标准做法）：本层键覆盖{@link Failed#reasonKey}，
     * 内层完整用户消息降级为{@link Failed#userDetail}（文案变长），
     * 技术性消息（{@link Failed#techDetail}/{@link Failed#cause}）原样透传<br>
     * 成功结果原样返回，不做任何事
     *
     * @param reasonKey 本层失败原因的翻译键
     * */
    default OperationResult causedBy(@Nullable String reasonKey) {
        if (this instanceof Failed f) {
            return new Failed(reasonKey, f.message(), f.techDetail(), f.cause());
        }
        return this;
    }

    /**
     * 键对版本的{@link #causedBy(String)}：按内层是否给得出用户消息，选用不同的本层键。<br>
     * 对应语言文件里“某操作失败（原因：{@code %s}）/某操作失败（原因未知）”这一对键；
     * 没有这对键的调用方直接用{@link #causedBy(String)}
     *
     * @param knownReasonKey   内层有用户消息（原因已知）时使用的本层翻译键
     * @param unknownReasonKey 内层没有用户消息（原因未知）时使用的本层翻译键
     * */
    default OperationResult causedBy(@NonNull String knownReasonKey, @Nullable String unknownReasonKey) {
        if (this instanceof Failed f) {
            Component inner = f.message();
            return inner == null
                    ? new Failed(unknownReasonKey, null, f.techDetail(), f.cause())
                    : new Failed(knownReasonKey, inner, f.techDetail(), f.cause());
        }
        return this;
    }

    // ------ 值结果转换 ------

    /**
     * 流程成功时附上产出值，转为携带值的结果；失败时原样保留失败信息<br>
     * 用于“内部步骤返回{@link OperationResult}、对外方法返回{@link ValueResult}”的分层写法
     *
     * @param value 流程成功时产出的值
     *
     * @see ValueResult
     * */
    default <T> @NonNull ValueResult<T> attachValue(@NonNull T value) {
        return this instanceof Failed(String reasonKey, Component userDetail, String techDetail, Throwable cause)
                ? new ValueResult.Failed<>(reasonKey, userDetail, techDetail, cause)
                : new ValueResult.WithValue<>(value);
    }

    // ------ 内部工具 ------

    /**
     * 按”键 + 参数”规则把失败原因转换为用户消息，供两个结果体系的{@code Failed}共用
     *
     * @param reasonKey  失败原因的翻译键，可为{@code null}
     * @param userDetail 翻译参数（内层用户文案），可为{@code null}
     *
     * @return 两者皆{@code null}时返回{@code null}（由最终调用方自行兜底）；
     *         只有键时生成无参文案；只有文案时原样返回；两者都有时把文案作为键的参数
     * */
    static @Nullable Component composeMessage(@Nullable String reasonKey, @Nullable Component userDetail) {
        if (reasonKey == null) return userDetail;
        if (userDetail == null) return Component.translatable(reasonKey);
        return Component.translatable(reasonKey, userDetail);
    }
}
