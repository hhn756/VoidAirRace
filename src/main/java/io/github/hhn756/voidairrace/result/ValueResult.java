package io.github.hhn756.voidairrace.result;

import net.kyori.adventure.text.Component;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 一次可能产出值的操作的结束状态
 * <p>
 * 它是不可变值。操作全部结束后交给调用方，用来描述“这次怎么结束的”
 * <p>
 * 结局有三种：
 * <ul>
 *     <li>{@link WithValue}：成功，且有值；</li>
 *     <li>{@link Empty}：成功，但合法地没有值；</li>
 *     <li>{@link Failed}：失败</li>
 * </ul>
 * <p>
 * 结局由类型本身表达，没有 success 布尔字段，{@link #isSuccess()}由 {@code instanceof} 推导<br>
 * {@link WithValue}的值必然非空<br>
 * 因此“失败却带值”“成功却没值却自称有值”这类非法状态无法构造<br>
 * <p>
 * 用法约定与{@link OperationResult}一致：<br>
 * 中间层不产生文案，只搬运键与消息，
 * 用到键对版本{@link #causedBy(String, String)}、
 * 必须有值时的{@link #expectValue(String, String)}<br>
 * 最终面向玩家的调用方才调用{@link #message()}转换为用户文案，
 * 面向日志的调用方调用{@link #techMessage()}获取技术性文本
 * <p>
 * 反直觉的一点：{@link Empty}在{@link #isSuccess()}上算成功，
 * 但{@link #hasValue()}为{@code false}，{@link #message()}返回{@code null}<br>
 * 所以判断“有没有值”必须用{@link #hasValue()}或模式匹配到{@link WithValue}，不能用{@link #isSuccess()}
 * <p>
 * 如果某个操作“必须有值”是调用前提，
 * 用{@link #expectValue(String, String)}把{@link Empty}（缺值）与{@link Failed}（失败）
 * 统一折叠成一个{@link OperationResult}失败
 *
 * @param <T> 值的类型
 *
 * @see OperationResult 不携带值时使用
 * */
public sealed interface ValueResult<T> permits ValueResult.WithValue, ValueResult.Empty, ValueResult.Failed {

    /**
     * 成功且产出了值
     *
     * @param value 操作产出的值，非空
     * */
    record WithValue<T>(@NonNull T value) implements ValueResult<T> {}

    /**
     * 成功，但本次操作合法地不产出值
     * <p>
     * 与{@link Failed}的不对称：本结局在{@link #isSuccess()}上算成功，
     * 但{@link #hasValue()}为{@code false}，且{@link #message()}返回{@code null}
     * */
    record Empty<T>() implements ValueResult<T> {}

    /**
     * 操作失败，字段职责与传播规则同{@link OperationResult.Failed}：
     * 用户消息（显示给玩家，禁止包含服务端参数）与技术性消息（仅日志，可包含服务端参数）两个通道
     * <p>
     * 四个字段各属一个通道、各有唯一职责，向上层传播时（见{@link #causedBy(String, String)}）规则不同：
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
     * @param userDetail 内层已转换好的用户文案，转换时作为{@code reasonKey}的参数，可为{@code null}。
     *                   禁止包含服务端参数
     * @param techDetail 本层技术性描述，仅供日志，可为{@code null}。
     *                   可包含服务端参数，禁止显示给玩家
     * @param cause      导致本次失败的内层异常，可为{@code null}。仅用于日志，不呈现给玩家
     * */
    record Failed<T>(
            @Nullable String reasonKey,
            @Nullable Component userDetail,
            @Nullable String techDetail,
            @Nullable Throwable cause
    ) implements ValueResult<T> {

        /**
         * 把本失败结果转换为用户消息（显示给玩家的文案）
         *
         * @return 转换规则见{@link OperationResult#composeMessage(String, Component)}
         * */
        public @Nullable Component message() {
            return OperationResult.composeMessage(reasonKey, userDetail);
        }

        /**
         * 把本失败结果转换为技术性消息（写入日志的文本），规则同{@link OperationResult.Failed#techMessage()}
         * */
        public @Nullable String techMessage() {
            if (techDetail == null) return cause == null ? null : cause.toString();
            return cause == null ? techDetail : techDetail + "（异常：" + cause + "）";
        }
    }

    // ------ 构造 ------

    /**
     * 创建一个成功且携带值的结果
     *
     * @param value 操作产出的值，不得为{@code null}
     * */
    static <T> @NonNull ValueResult<T> success(@NonNull T value) {
        return new WithValue<>(value);
    }

    /**
     * 创建一个成功但无值的结果
     * */
    static <T> @NonNull ValueResult<T> empty() {
        return new Empty<>();
    }

    /**
     * 创建一个失败结果（用户消息只有翻译键，无技术性消息）
     *
     * @param reasonKey 本层失败原因的翻译键，可为{@code null}（无可上报原因的失败）
     * */
    static <T> @NonNull ValueResult<T> failure(@Nullable String reasonKey) {
        return new Failed<>(reasonKey, null, null, null);
    }

    /**
     * 创建一个失败结果，同时给出两种消息
     *
     * @param reasonKey  本层失败原因的翻译键（用户消息），可为{@code null}
     * @param techDetail 本层技术性描述，可含服务端参数，仅日志；可为{@code null}
     * */
    static <T> @NonNull ValueResult<T> failure(@Nullable String reasonKey, @Nullable String techDetail) {
        return new Failed<>(reasonKey, null, techDetail, null);
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
    static <T> @NonNull ValueResult<T> failure(
            @Nullable String reasonKey,
            @Nullable Component userDetail,
            @Nullable String techDetail,
            @Nullable Throwable cause
    ) {
        return new Failed<>(reasonKey, userDetail, techDetail, cause);
    }

    /**
     * 由无值结果构造带值结果：失败信息原样透传，成功（{@link OperationResult.Ok}）转为{@link Empty}
     * （成功但尚无值）<br>
     * 用于“内部步骤返回{@link OperationResult}、对外方法返回{@code ValueResult}”的分层写法
     * */
    static <T> @NonNull ValueResult<T> fromOperation(@NonNull OperationResult source) {
        return source instanceof OperationResult.Failed(var key, var userDetail, var techDetail, var cause)
                ? new Failed<>(key, userDetail, techDetail, cause)
                : new Empty<>();
    }

    // ------ 查询 ------

    /**
     * 操作是否成功。注意：成功不代表有值——{@link Empty}也返回{@code true}
     *
     * @return 结局为{@link WithValue}或{@link Empty}时返回{@code true}；
     *         判断“有没有值”请用{@link #hasValue()}
     */
    default boolean isSuccess() {
        return this instanceof WithValue<T> || this instanceof Empty<T>;
    }

    /**
     * 本次成功是否产出了值
     *
     * @return 结局为{@link WithValue}时返回{@code true}；
     *         {@link Empty}虽算成功但返回{@code false}
     */
    default boolean hasValue() {
        return this instanceof WithValue<T>;
    }

    /**
     * 获取产出的值。仅{@link WithValue}携带值
     *
     * @return 结局为{@link WithValue}时返回该值，否则返回{@code null}
     * */
    default @Nullable T value() {
        return this instanceof WithValue<T>(T value) ? value : null;
    }

    /**
     * 获取本结果的用户消息，供最终面向玩家的调用方使用<br>
     * 注意：成功结果（含{@link Empty}）都返回{@code null}，所以不能凭本方法区分“无值”与“失败”
     *
     * @return 结局为{@link Failed}时同{@link Failed#message()}；成功时为{@code null}
     * */
    default @Nullable Component message() {
        return this instanceof Failed<T> f ? f.message() : null;
    }

    /**
     * 获取本结果的技术性消息，供日志与控制台使用。可包含服务端参数，禁止显示给玩家<br>
     * 注意：成功结果（含{@link Empty}）都返回{@code null}，所以不能凭本方法区分“无值”与“失败”
     *
     * @return 结局为{@link Failed}时同{@link Failed#techMessage()}；成功时为{@code null}
     * */
    default @Nullable String techMessage() {
        return this instanceof Failed<T> f ? f.techMessage() : null;
    }

    // ------ 传播与转换 ------

    /**
     * 把内层失败包装成本层失败（中间层向上传播的标准做法）：按内层是否给得出用户消息，选用不同的本层键<br>
     * 对应语言文件里“某操作失败（原因：{@code %s}）/某操作失败（原因未知）”这一对键；
     * 本层键覆盖{@link Failed#reasonKey}，
     * 内层完整用户消息降级为{@link Failed#userDetail}（文案变长），
     * 技术性消息（{@link Failed#techDetail}/{@link Failed#cause}）原样透传<br>
     * 成功结局（{@link WithValue}/{@link Empty}）原样返回，不做任何事
     *
     * @param knownReasonKey   内层有用户消息（原因已知）时使用的本层翻译键
     * @param unknownReasonKey 内层没有用户消息（原因未知）时使用的本层翻译键
     * */
    default @NonNull ValueResult<T> causedBy(@NonNull String knownReasonKey, @Nullable String unknownReasonKey) {
        if (this instanceof Failed<T> f) {
            Component inner = f.message();
            return inner == null
                    ? new Failed<>(unknownReasonKey, null, f.techDetail(), f.cause())
                    : new Failed<>(knownReasonKey, inner, f.techDetail(), f.cause());
        }
        return this;
    }

    /**
     * 转为无值结果：失败信息原样透传（两种消息通道都保留），{@link WithValue}与{@link Empty}
     * 都折叠为{@link OperationResult.Ok}（值被丢弃）<br>
     * 仅适用于“值可有可无都算成功”的场合；值必须存在时用{@link #expectValue(String, String)}
     * */
    default @NonNull OperationResult toOperation() {
        if (this instanceof Failed<T>(String reasonKey, Component userDetail, String techDetail, Throwable cause)) {
            return new OperationResult.Failed(reasonKey, userDetail, techDetail, cause);
        }
        return new OperationResult.Ok();
    }

    /**
     * 本结果用于“必须有值”的流程：把非{@link WithValue}结局折叠成一个{@link OperationResult}失败
     * <ul>
     *     <li>{@link WithValue} → {@link OperationResult.Ok}（值不会被本方法交给你，请在调用前先取出）；</li>
     *     <li>{@link Empty} → 以{@code unknownReasonKey}为原因的失败（成功却没给出值，按“原因未知”类文案处理）；</li>
     *     <li>{@link Failed} → 按{@link #causedBy(String, String)}的键对规则包装，技术性消息原样透传。</li>
     * </ul>
     *
     * @param knownReasonKey   内层有用户消息（原因已知）时使用的本层翻译键
     * @param unknownReasonKey 内层没有用户消息（原因未知）或缺值（{@link Empty}）时使用的本层翻译键
     * */
    default @NonNull OperationResult expectValue(@Nullable String knownReasonKey, @Nullable String unknownReasonKey) {
        return switch (this) {
            case WithValue<T> v -> new OperationResult.Ok();
            case Empty<T> e -> new OperationResult.Failed(unknownReasonKey, null, null, null);
            case Failed<T> f -> {
                Component inner = f.message();
                yield inner == null
                        ? new OperationResult.Failed(unknownReasonKey, null, f.techDetail(), f.cause())
                        : new OperationResult.Failed(knownReasonKey, inner, f.techDetail(), f.cause());
            }
        };
    }
}
