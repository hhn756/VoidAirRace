package io.github.hhn756.voidairrace.core.addons.usrpackage;

import org.jspecify.annotations.NonNull;

import java.nio.file.Path;
import java.util.List;

/**
 * 代表一个用户包并记录其基本信息<br>
 * 数据来源是包元文件（{@code pack.varmeta}）
 * */
public class UsrPackage {
    /** 用户包的Id，重复Id不可加载 */
    private final @NonNull String id;
    /** 包自身声明的逻辑版本，正整数，用于判断新旧（值越大越新） */
    private final int version;
    /** 入口脚本路径，相对包内脚本目录 /scripts/ */
    private final @NonNull String entry;
    /** 对插件 API 版本的约束区间（闭区间） */
    private final @NonNull PackageApiVersion api;
    /** 依赖的包 Id 列表（不可变），元文件未声明时为空列表 */
    private final @NonNull List<@NonNull String> dependencies;
    /** 包根（目录形式为磁盘目录，zip 形式为挂载文件系统内的包根并记录外层 zip 路径） */
    private final @NonNull PackageRoot root;

    /**
     * 由包管理器自动管理，通过包管理器获取实例
     * */
    UsrPackage(
            @NonNull String id,
            int version,
            @NonNull String entry,
            @NonNull PackageApiVersion api,
            @NonNull List<@NonNull String> dependencies,
            @NonNull PackageRoot root
    ) {
        this.id = id;
        this.version = version;
        this.entry = entry;
        this.api = api;
        this.dependencies = dependencies;
        this.root = root;
    }

    /**
     * @return 包的id，不会与其他已加载的包重复
     * */
    public @NonNull String id() {
        return id;
    }

    /**
     * @return 包自身声明的逻辑版本
     * */
    public int version() {
        return version;
    }

    /**
     * @return 入口脚本路径，相对包内脚本目录 /scripts/
     * */
    public @NonNull String entry() {
        return entry;
    }

    /**
     * @return 对插件 API 版本的约束区间（闭区间）
     * */
    public @NonNull PackageApiVersion api() {
        return api;
    }

    /**
     * @return 依赖的包 Id 列表（不可变），未声明依赖时为空列表
     * */
    public @NonNull List<@NonNull String> dependencies() {
        return dependencies;
    }

    /**
     * @return 包根：目录包为磁盘目录，zip 包为挂载文件系统内的包根（外层 zip 路径见 {@link PackageRoot#zipPath()}）
     * */
    public @NonNull PackageRoot root() {
        return root;
    }

    // ------ 关键成员的直接委托（缩短调用链） ------

    /**
     * @return 包根的实际操作路径（目录包为磁盘目录，zip 包为挂载文件系统内的路径）
     * */
    public @NonNull Path path() {
        return root.path();
    }

    /**
     * @return 包根的实际操作路径下脚本目录（{@code /scripts/}），入口脚本与 require 模块都位于此
     * */
    public @NonNull Path scriptsDirectory() {
        return resolve("scripts");
    }

    /**
     * 解析相对包根的路径为实际路径（不做语法与越界校验，见 {@link PackageRoot#resolve(String)}）
     *
     * @param relPath 相对包根的路径，可含多级目录
     *
     * @return 实际路径
     * */
    public @NonNull Path resolve(@NonNull String relPath) {
        return root.resolve(relPath);
    }

    /**
     * @return 日志友好的完整来源描述（目录包为磁盘路径，zip 包为 {@code zip路径!内部包根}）
     * */
    public @NonNull String describe() {
        return root.describe();
    }

    /**
     * 判断指定的插件 API 版本是否落在本包声明的支持区间内
     *
     * @param apiVersion 插件当前的 API 版本号
     *
     * @return 版本号受支持返回{@code true}
     * */
    public boolean supportsApiVersion(int apiVersion) {
        return api.supports(apiVersion);
    }
}
