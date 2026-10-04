package io.github.hhn756.voidairrace.core.addons.usrpackage;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.exception.UsrPackageException;
import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import io.github.hhn756.voidairrace.result.ValueResult;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 负责在插件启用时自动加载用户包目录下的所有用户包<br>
 * 支持两种包形式：目录形式（直接以该目录为包根）与 .zip 压缩形式（挂载为只读文件系统后加载，
 * 包根优先取挂载根，若元文件仅位于唯一的顶层子目录内则取该子目录）
 * */
public class PackageLoader implements Module {
    /**
     * 用户包目录名，位于插件数据目录下
     * */
    private static final String PACKAGES_DIR_NAME = "packages";

    /**
     * zip 包文件名的后缀（不区分大小写）
     * */
    private static final String ZIP_SUFFIX = ".zip";

    @Override
    public Collection<Class<? extends Module>> getRequiredModules() {
        return List.of(PackageManager.class);
    }

    /**
     * 已挂载的 zip 包：zip 文件路径与对应的挂载文件系统<br>
     * 包加载成功后其根目录在本次启用中仍会被读取（如后续读取入口脚本），因此挂载保持到插件禁用，
     * 由 {@link #onUnload()} 统一关闭；加载失败的挂载则立即关闭
     * */
    private record MountedZip(@NonNull Path zipPath, @NonNull FileSystem fileSystem) {}

    private final @NonNull List<@NonNull MountedZip> mountedZips = new ArrayList<>();

    /**
     * 插件启用时执行：扫描用户包目录，加载其中的所有用户包（目录形式或 .zip 压缩形式）<br>
     * 单个包加载失败（无论可预期失败还是意外失败）只记录日志，继续处理其余条目
     * */
    private void onLoad(@NonNull PackageManager packageManager) {
        Logger logger = VoidAirRace.getInstance().getLogger();

        Path packagesDir = VoidAirRace.getInstance().getDataFolder().toPath().resolve(PACKAGES_DIR_NAME);
        if (!Files.isDirectory(packagesDir)) {
            try {
                Files.createDirectories(packagesDir);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "无法创建用户包目录 " + packagesDir + "，本次启用将不加载任何用户包", e);
                return;
            }
        }

        List<Path> entries;
        try (var paths = Files.list(packagesDir)) {
            entries = paths.sorted().toList();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "读取用户包目录 " + packagesDir + " 失败，本次启用将不加载任何用户包", e);
            return;
        }

        int loadedCount = 0;
        for (Path entry : entries) {
            // 目录包：直接以该目录为包根加载
            if (Files.isDirectory(entry)) {
                if (loadAndReport(packageManager, PackageRoot.ofDirectory(entry), logger)) loadedCount++;
                continue;
            }
            // zip 包：挂载为只读文件系统，解析出包根后加载，与目录包共用加载代码；其余文件忽略
            if (!isZipFile(entry)) continue;
            FileSystem fs = mountZip(entry, logger);
            if (fs == null) continue;
            Path rootInZip = resolveZipPackageRoot(fs.getPath("/"), logger);
            if (rootInZip == null) {
                logger.warning("跳过用户包 " + entry + "（zip 包）：压缩包内未找到 " + PackageManager.META_FILE_NAME
                        + "（顶层没有元文件，也不是仅含单个子目录的包装形式）");
                closeZip(fs, entry, logger);
                continue;
            }
            if (loadAndReport(packageManager, PackageRoot.ofZip(entry, rootInZip), logger)) {
                mountedZips.add(new MountedZip(entry, fs)); // 保持挂载，插件禁用时统一关闭
                loadedCount++;
            } else {
                closeZip(fs, entry, logger); // 加载失败，立即关闭挂载避免泄漏
            }
        }

        logger.info("用户包自动加载完成，共加载 " + loadedCount + " 个");
    }

    /**
     * 插件禁用时执行：关闭所有为 zip 包挂载的文件系统<br>
     * 包的注册状态随 {@link PackageManager} 一并失效，无需额外清理
     * */
    private void onUnload() {
        Logger logger = VoidAirRace.getInstance().getLogger();
        for (MountedZip mounted : mountedZips) {
            closeZip(mounted.fileSystem(), mounted.zipPath(), logger);
        }
        mountedZips.clear();
    }

    // ------ 内部辅助方法 ------

    /**
     * 挂载一个 zip 用户包为只读文件系统
     *
     * @param zipPath zip 文件路径
     * @param logger 日志器
     *
     * @return 挂载成功返回文件系统；失败返回{@code null}（已记录日志）
     * */
    private static @Nullable FileSystem mountZip(@NonNull Path zipPath, @NonNull Logger logger) {
        try {
            // 只读挂载：包内容不允许被修改
            return FileSystems.newFileSystem(zipPath, Map.of("readOnly", "true"));
        } catch (IOException | ProviderNotFoundException e) {
            logger.log(Level.SEVERE, "挂载用户包压缩文件 " + zipPath + " 失败，已跳过", e);
            return null;
        }
    }

    /**
     * 解析 zip 包内的实际包根：优先挂载根本身（元文件直接位于压缩包根目录）；<br>
     * 否则当顶层恰好只有一个子目录且其中含元文件时取该子目录，
     * 兼容“文件夹套一层再压缩”的常见打包方式
     *
     * @param zipRoot zip 挂载文件系统的根路径
     * @param logger 日志器
     *
     * @return 解析成功返回包根路径；确定不是用户包时返回{@code null}
     * */
    private static @Nullable Path resolveZipPackageRoot(@NonNull Path zipRoot, @NonNull Logger logger) {
        if (Files.isRegularFile(zipRoot.resolve(PackageManager.META_FILE_NAME))) return zipRoot;

        List<Path> topLevel;
        try (var paths = Files.list(zipRoot)) {
            topLevel = paths.toList();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "读取 zip 包顶层目录 " + zipRoot + " 失败", e);
            return null;
        }

        if (topLevel.size() == 1 && Files.isDirectory(topLevel.getFirst())) {
            Path candidate = topLevel.getFirst();
            if (Files.isRegularFile(candidate.resolve(PackageManager.META_FILE_NAME))) return candidate;
        }
        return null;
    }

    /**
     * 判断一个目录项是否是 zip 包文件
     * */
    private static boolean isZipFile(@NonNull Path entry) {
        return Files.isRegularFile(entry)
                && entry.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(ZIP_SUFFIX);
    }

    /**
     * 加载一个用户包并记录结果日志，目录包与 zip 包共用
     *
     * @param packageManager 包管理器
     * @param packageRoot 包根（含来源描述）
     * @param logger 日志器
     *
     * @return 加载成功返回{@code true}
     * */
    private static boolean loadAndReport(
            @NonNull PackageManager packageManager,
            @NonNull PackageRoot packageRoot,
            @NonNull Logger logger
    ) {
        String label = packageRoot.describe();
        ValueResult<UsrPackage> result;
        try {
            result = packageManager.load(packageRoot);
        } catch (UsrPackageException e) {
            // 意外失败（读取元文件的 IO 异常等）
            logger.log(Level.SEVERE, "自动加载用户包 " + label + " 时发生意外错误：" + e.getMessage(), e);
            return false;
        }

        if (result.hasValue()) {
            logger.info("已加载用户包：" + result.value().id() + "（来源：" + label + "）");
            return true;
        }
        logger.warning("跳过用户包 " + label + "：" + result.techMessage());
        return false;
    }

    /**
     * 关闭一个 zip 包的挂载文件系统
     * */
    private static void closeZip(@NonNull FileSystem fs, @NonNull Path zipPath, @NonNull Logger logger) {
        try {
            fs.close();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "关闭用户包压缩文件 " + zipPath + " 的挂载文件系统失败", e);
        }
    }
}
