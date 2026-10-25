package io.github.hhn756.voidairrace.service.language;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 服务端语言：全局设置中 {@code language} 字段的合法取值，同时也是内置资源包语言文件的文件名
 * */
public enum Language {
    ZH_CN("zh_cn"),
    EN_US("en_us");

    private final @NonNull String code;

    Language(@NonNull String code) {
        this.code = code;
    }

    /**
     * @return 语言代码，等于资源包 {@code assets/minecraft/lang/} 下对应语言文件的文件名（不含后缀）
     * */
    public @NonNull String code() {
        return code;
    }

    /**
     * 按语言代码查找枚举值
     *
     * @param code 语言代码，允许为{@code null}
     *
     * @return 匹配的枚举值；{@code null} 或无法识别时返回 {@code null}
     * */
    public static @Nullable Language fromCode(@Nullable String code) {
        for (Language language : values()) {
            if (language.code.equals(code)) return language;
        }
        return null;
    }

    /**
     * @return 全部合法语言代码的单行逗号分隔列表，用于提示可选项
     * */
    public static @NonNull String supportedCodes() {
        return Arrays.stream(values())
                .map(Language::code)
                .collect(Collectors.joining(", "));
    }
}
