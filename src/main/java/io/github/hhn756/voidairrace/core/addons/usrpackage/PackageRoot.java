package io.github.hhn756.voidairrace.core.addons.usrpackage;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.file.FileSystems;
import java.nio.file.Path;

/**
 * 表示一个用户包的包根：既能取到可用于实际读写操作的路径，也能描述完整的来源信息<br>
 * 包形式有两种：
 * <ul>
 *   <li>目录形式：操作路径是磁盘上的目录路径，来源即该路径（{@link PackageRoot#zipPath}为{@code null}）</li>
 *   <li>zip 压缩形式：操作路径是挂载文件系统内的包根路径，来源是外层 zip 文件路径
 *       与内部包根路径的组合（{@code zip路径!内部包根}，形如 {@code foo.zip!/inner}）</li>
 * </ul>
 * 实例由包管理器与包加载器经{@link #ofDirectory}/{@link #ofZip}构造，随包对象存在<br>
 * 值语义：相等性由两个成员共同决定
 * */
public record PackageRoot(@NonNull Path path, @Nullable Path zipPath) {
    /**
     * 规范构造器校验：zip 形态的操作路径必须位于挂载文件系统内<br>
     * 防止两参次序被颠倒（误把磁盘上的 zip 文件路径当作操作路径）
     * */
    public PackageRoot {
        if (zipPath != null && path.getFileSystem() == FileSystems.getDefault()) {
            throw new IllegalArgumentException("zip 形态的包根路径必须位于挂载文件系统内：" + path);
        }
    }

    /**
     * 以磁盘目录为包根（目录形式）
     * */
    static @NonNull PackageRoot ofDirectory(@NonNull Path dir) {
        return new PackageRoot(dir, null);
    }

    /**
     * 以 zip 挂载文件系统内的路径为包根（zip 压缩形式）
     * */
    static @NonNull PackageRoot ofZip(@NonNull Path zipPath, @NonNull Path rootInZip) {
        return new PackageRoot(rootInZip, zipPath);
    }

    /**
     * @return 是否为 zip 压缩形式的包
     * */
    public boolean isZip() {
        return zipPath != null;
    }

    /**
     * @return 日志友好的完整来源描述：目录包为磁盘路径，zip 包为 {@code zip路径!内部包根}
     * */
    public @NonNull String describe() {
        return zipPath == null ? path.toString() : zipPath + "!" + path;
    }

    /**
     * 解析相对包根的路径为实际路径<br>
     * 不做语法与越界校验——路径合法性由统一资源访问入口（PackageAccess 的路径白名单）裁定，
     * 本方法只负责「包根 + 相对路径 → 实际路径」这一步计算
     *
     * @param relPath 相对包根的路径，可含多级目录（{@code /} 分隔）
     *
     * @return 实际路径（位于包根所在的文件系统内）
     * */
    public @NonNull Path resolve(@NonNull String relPath) {
        return path.resolve(relPath);
    }

    @Override
    public @NonNull String toString() {
        return describe();
    }
}
