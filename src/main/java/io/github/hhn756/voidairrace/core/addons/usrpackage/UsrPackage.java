package io.github.hhn756.voidairrace.core.addons.usrpackage;

import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * 代表一个用户包并记录其基本信息<br>
 * 数据来源是包元文件（{@code pack.varmeta}）的四个引导字段
 * */
public class UsrPackage {
    /** 用户包的Id，重复Id不可加载 */
    private final @NonNull String id;
    /** 包自身声明的逻辑版本，正整数，用于判断新旧（值越大越新） */
    private final int version;
    /** 入口脚本路径，相对包内脚本目录 /scripts/ */
    private final @NonNull String entry;
    /** 对插件 API 版本的约束，第 1 项为最小版本号，第 2 项为最大版本号 */
    private final @NonNull List<@NonNull Integer> api;
    /** 包根（目录形式为磁盘目录，zip 形式为挂载文件系统内的包根并记录外层 zip 路径） */
    private final @NonNull PackageRoot root;

    /**
     * 由包管理器自动管理，通过包管理器获取实例
     * */
    UsrPackage(
            @NonNull String id,
            int version,
            @NonNull String entry,
            @NonNull List<@NonNull Integer> api,
            @NonNull PackageRoot root
    ) {
        this.id = id;
        this.version = version;
        this.entry = entry;
        this.api = api;
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
     * @return 对插件 API 版本的约束（不可变），第 1 项为最小版本号，第 2 项为最大版本号
     * */
    public @NonNull List<@NonNull Integer> api() {
        return api;
    }

    /**
     * @return 包根：目录包为磁盘目录，zip 包为挂载文件系统内的包根（外层 zip 路径见 {@link PackageRoot#zipPath()}）
     * */
    public @NonNull PackageRoot root() {
        return root;
    }
}
