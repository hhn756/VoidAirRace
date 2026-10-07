package io.github.hhn756.voidairrace.core.addons.usrpackage;

import org.jspecify.annotations.NonNull;

/**
 * 用户包对插件 API 版本的约束：闭区间 {@code [min, max]}，来源是包元文件的 {@code api} 字段<br>
 * 插件当前 API 版本落在区间内才允许加载该包
 *
 * @param min 支持的最小 API 版本号（正整数）
 * @param max 支持的最大 API 版本号（正整数，不小于{@code min}）
 * */
public record PackageApiVersion(int min, int max) {
    /**
     * 规范构造器校验：版本号必须为正整数且区间不倒置
     * */
    public PackageApiVersion {
        if (min < 1 || max < 1) throw new IllegalArgumentException("API 版本号必须是正整数：" + min + ", " + max);
        if (min > max) throw new IllegalArgumentException("最小 API 版本号不能大于最大版本号：" + min + " > " + max);
    }

    /**
     * 判断指定的插件 API 版本是否落在本约束区间内
     *
     * @param apiVersion 插件当前的 API 版本号
     *
     * @return 版本号落在 {@code [min, max]} 内返回{@code true}
     * */
    public boolean supports(int apiVersion) {
        return apiVersion >= min && apiVersion <= max;
    }

    @Override
    public @NonNull String toString() {
        return "[" + min + ", " + max + "]";
    }
}
