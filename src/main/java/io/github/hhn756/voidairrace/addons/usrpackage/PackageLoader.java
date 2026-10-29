package io.github.hhn756.voidairrace.addons.usrpackage;

import io.github.hhn756.voidairrace.Voidairrace;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.infrastructure.language.ServerLanguage;
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
    private static final @NonNull String PACKAGES_DIR_NAME = "packages";

    /**
     * zip 包文件名的后缀（不区分大小写）
     * */
    private static final @NonNull String ZIP_SUFFIX = ".zip";

    @Override
    public @NonNull Collection<@NonNull Class<? extends Module>> getRequiredModules() {
        return List.of(PackageManager.class);
    }

    /**
     * 已挂载的 zip 包：zip 文件路径与对应的挂载文件系统<br>
     * 挂载保持到插件禁用：已加载的包在运行期仍要读取包内文件，被卸载的包也可能重新启用；
     * 由 {@link #onUnload()} 统一关闭
     * */
    private record MountedZip(@NonNull Path zipPath, @NonNull FileSystem fileSystem) {}

    private final @NonNull List<@NonNull MountedZip> mountedZips = new ArrayList<>();

    /**
     * 插件启用时执行：扫描用户包目录，解析其中的所有用户包（目录形式或 .zip 压缩形式），
     * 一次性交给包管理器按依赖拓扑排序批量加载<br>
     * 单个包加载失败（无论可预期失败还是意外失败）只记录日志，不影响其余包
     * */
    private void onLoad(@NonNull PackageManager packageManager) {
        Logger logger = Voidairrace.getInstance().getLogger();
        List<PackageManager.PackageLoadResult> results = packageManager.load(scan());

        // 紧凑汇报：同类结果合并为一行，避免逐包刷屏；意外异常（含堆栈）仍逐条记录
        // 未启用与其他失败的条目以来源简称标识（失败结果不携带包 Id），已加载条目以包 Id 标识
        List<String> loadedIds = new ArrayList<>();
        List<String> notEnabledNames = new ArrayList<>();
        Map<String, List<String>> failureGroups = new LinkedHashMap<>(); // 失败键 → 各项技术性消息
        for (PackageManager.PackageLoadResult loadResult : results) {
            if (loadResult.exception() != null) {
                logger.log(Level.SEVERE, ServerLanguage.translate(
                        TranslateKeys.Log.PACKAGE_LOADER_UNEXPECTED_ERROR,
                        loadResult.root().describe(), loadResult.exception().getMessage()), loadResult.exception());
                continue;
            }
            ValueResult<UsrPackage> result = loadResult.result();
            if (result == null) continue; // 结果与异常必居其一（PackageLoadResult 契约），防御性跳过
            if (result.hasValue()) {
                loadedIds.add(result.value().id());
            } else if (result instanceof ValueResult.Failed<UsrPackage> failed) {
                if (TranslateKeys.Addons.USR_PACKAGE_PACKAGE_NOT_ENABLED.equals(failed.reasonKey())) {
                    notEnabledNames.add(sourceName(loadResult.root()));
                } else {
                    String techMessage = failed.techMessage();
                    failureGroups.computeIfAbsent(failed.reasonKey(), key -> new ArrayList<>())
                            .add(techMessage == null ? sourceName(loadResult.root()) : techMessage);
                }
            }
        }
        if (loadedIds.isEmpty()) {
            logger.info(ServerLanguage.translate(
                    TranslateKeys.Log.PACKAGE_LOADER_NONE_LOADED, results.size()));
        } else {
            logger.info(ServerLanguage.translate(
                    TranslateKeys.Log.PACKAGE_LOADER_LOADED_SUMMARY,
                    loadedIds.size(), results.size(), String.join("、", loadedIds)));
        }
        if (!notEnabledNames.isEmpty()) {
            logger.warning(ServerLanguage.translate(
                    TranslateKeys.Log.PACKAGE_LOADER_NOT_ENABLED_SKIPPED, String.join("、", notEnabledNames)));
        }
        for (Map.Entry<String, List<String>> group : failureGroups.entrySet()) {
            logger.warning(ServerLanguage.translate(
                    TranslateKeys.Log.PACKAGE_LOADER_FAILED_GROUP,
                    failureLabel(group.getKey()), String.join("；", group.getValue())));
        }
    }

    /**
     * 扫描用户包目录，返回其中全部包根<br>
     * 目录包直接以目录为包根；zip 包挂载为只读文件系统后解析出包根，与目录包共用后续加载代码。
     * 能解析出包根的 zip 挂载保持到插件禁用，解析失败（不是合法用户包）的挂载立即关闭
     * <p>
     * 供插件启用时的自动加载与“用户包”命令共用；命令每次调用都会重新扫描，运行期放入的包因此可见
     *
     * @return 扫描到的包根，按目录项路径排序；包目录创建或读取失败时为空列表
     * */
    public @NonNull List<@NonNull PackageRoot> scan() {
        Logger logger = Voidairrace.getInstance().getLogger();

        Path packagesDir = Voidairrace.getInstance().getDataFolder().toPath().resolve(PACKAGES_DIR_NAME);
        if (!Files.isDirectory(packagesDir)) {
            try {
                Files.createDirectories(packagesDir);
            } catch (IOException e) {
                logger.log(Level.SEVERE, ServerLanguage.translate(
                        TranslateKeys.Log.PACKAGE_LOADER_DIR_CREATE_FAILED, packagesDir), e);
                return List.of();
            }
        }

        List<Path> entries;
        try (var paths = Files.list(packagesDir)) {
            entries = paths.sorted().toList();
        } catch (IOException e) {
            logger.log(Level.SEVERE, ServerLanguage.translate(
                    TranslateKeys.Log.PACKAGE_LOADER_DIR_READ_FAILED, packagesDir), e);
            return List.of();
        }

        List<PackageRoot> roots = new ArrayList<>();
        for (Path entry : entries) {
            // 目录包：直接以该目录为包根
            if (Files.isDirectory(entry)) {
                roots.add(PackageRoot.ofDirectory(entry));
                continue;
            }
            // zip 包：挂载为只读文件系统，解析出包根，与目录包共用加载代码；其余文件忽略
            if (!isZipFile(entry)) continue;
            FileSystem fs = mountOrReuse(entry, logger);
            if (fs == null) continue;
            Path rootInZip = resolveZipPackageRoot(fs.getPath("/"), logger);
            if (rootInZip == null) {
                logger.warning(ServerLanguage.translate(
                        TranslateKeys.Log.PACKAGE_LOADER_ZIP_META_MISSING, entry, PackageManager.META_FILE_NAME));
                closeZip(fs, entry, logger);
                continue;
            }
            roots.add(PackageRoot.ofZip(rootInZip, entry));
        }
        return roots;
    }

    /**
     * 插件禁用时执行：关闭所有为 zip 包挂载的文件系统<br>
     * 包的注册状态随 {@link PackageManager} 一并失效，无需额外清理
     * */
    private void onUnload() {
        Logger logger = Voidairrace.getInstance().getLogger();
        for (MountedZip mounted : mountedZips) {
            closeZip(mounted.fileSystem(), mounted.zipPath(), logger);
        }
        mountedZips.clear();
    }

    // ------ 内部辅助方法 ------

    /** 失败原因翻译键 → 汇报行里简短类别标签的翻译键（未登记的键原样输出） */
    private static final @NonNull Map<@NonNull String, @NonNull String> FAILURE_LABEL_KEYS = Map.ofEntries(
            Map.entry(TranslateKeys.Addons.USR_PACKAGE_DIR_NOT_FOUND,
                    TranslateKeys.Log.PACKAGE_LOADER_LABEL_DIR_NOT_FOUND),
            Map.entry(TranslateKeys.Addons.USR_PACKAGE_NOT_A_PACKAGE,
                    TranslateKeys.Log.PACKAGE_LOADER_LABEL_NOT_A_PACKAGE),
            Map.entry(TranslateKeys.Addons.USR_PACKAGE_META_SIZE_ERROR,
                    TranslateKeys.Log.PACKAGE_LOADER_LABEL_META_SIZE_ERROR),
            Map.entry(TranslateKeys.Addons.USR_PACKAGE_META_FIELD_FORMAT_ERROR,
                    TranslateKeys.Log.PACKAGE_LOADER_LABEL_META_FIELD_FORMAT_ERROR),
            Map.entry(TranslateKeys.Addons.USR_PACKAGE_DEPENDENCY_CYCLE,
                    TranslateKeys.Log.PACKAGE_LOADER_LABEL_DEPENDENCY_CYCLE),
            Map.entry(TranslateKeys.Addons.USR_PACKAGE_ID_DUPLICATE,
                    TranslateKeys.Log.PACKAGE_LOADER_LABEL_ID_DUPLICATE),
            Map.entry(TranslateKeys.Addons.USR_PACKAGE_API_VERSION_UNSUPPORTED,
                    TranslateKeys.Log.PACKAGE_LOADER_LABEL_API_VERSION_UNSUPPORTED),
            Map.entry(TranslateKeys.Addons.USR_PACKAGE_DEPENDENCY_MISSING,
                    TranslateKeys.Log.PACKAGE_LOADER_LABEL_DEPENDENCY_MISSING),
            Map.entry(TranslateKeys.Addons.USR_PACKAGE_ENTRY_SCRIPT_FAILED,
                    TranslateKeys.Log.PACKAGE_LOADER_LABEL_ENTRY_SCRIPT_FAILED)
    );

    /**
     * 取失败原因对应的简短类别标签
     *
     * @param reasonKey 失败结果的翻译键，允许为{@code null}（视为未知原因）
     *
     * @return 登记过的键返回对应标签的当前语言文本，否则返回键原文；{@code null} 键返回「未知原因」
     * */
    private static @NonNull String failureLabel(@Nullable String reasonKey) {
        if (reasonKey == null) {
            return ServerLanguage.translate(TranslateKeys.Log.PACKAGE_LOADER_LABEL_UNKNOWN);
        }
        String labelKey = FAILURE_LABEL_KEYS.get(reasonKey);
        return ServerLanguage.translate(labelKey == null ? reasonKey : labelKey);
    }

    /**
     * 取包来源的简称：目录包为目录名，zip 包为 zip 文件名（不含上级路径）
     *
     * @param root 包根
     *
     * @return 来源简称
     * */
    public static @NonNull String sourceName(@NonNull PackageRoot root) {
        Path fileName = root.isZip() ? root.zipPath().getFileName() : root.path().getFileName();
        return String.valueOf(fileName);
    }

    /**
     * 取得 zip 包对应的挂载文件系统：同一 zip 已有打开的挂载时复用，否则重新挂载<br>
     * 关闭的旧记录先清掉，避免重复扫描（本插件启用期间只可能因插件禁用而关闭）导致多次挂载同一 zip
     *
     * @param zipPath zip 文件路径
     * @param logger  日志器
     *
     * @return 文件系统；挂载失败返回{@code null}并记录日志
     * */
    private @Nullable FileSystem mountOrReuse(@NonNull Path zipPath, @NonNull Logger logger) {
        mountedZips.removeIf(mounted -> mounted.zipPath().equals(zipPath) && !mounted.fileSystem().isOpen());
        for (MountedZip mounted : mountedZips) {
            if (mounted.zipPath().equals(zipPath)) return mounted.fileSystem();
        }
        FileSystem fs = mountZip(zipPath, logger);
        if (fs != null) mountedZips.add(new MountedZip(zipPath, fs));
        return fs;
    }

    /**
     * 挂载一个 zip 用户包为只读文件系统
     *
     * @param zipPath zip 文件路径
     * @param logger 日志器
     *
     * @return 挂载成功返回文件系统；失败返回{@code null}并记录日志
     * */
    private static @Nullable FileSystem mountZip(@NonNull Path zipPath, @NonNull Logger logger) {
        try {
            // 只读挂载：包内容不允许被修改
            return FileSystems.newFileSystem(zipPath, Map.of("readOnly", "true"));
        } catch (IOException | ProviderNotFoundException e) {
            logger.log(Level.SEVERE, ServerLanguage.translate(
                    TranslateKeys.Log.PACKAGE_LOADER_ZIP_MOUNT_FAILED, zipPath), e);
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
            logger.log(Level.SEVERE, ServerLanguage.translate(
                    TranslateKeys.Log.PACKAGE_LOADER_ZIP_TOP_LEVEL_READ_FAILED, zipRoot), e);
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
     * 关闭一个 zip 包的挂载文件系统
     * */
    private static void closeZip(@NonNull FileSystem fs, @NonNull Path zipPath, @NonNull Logger logger) {
        try {
            fs.close();
        } catch (IOException e) {
            logger.log(Level.SEVERE, ServerLanguage.translate(
                    TranslateKeys.Log.PACKAGE_LOADER_ZIP_CLOSE_FAILED, zipPath), e);
        }
    }
}
