package io.github.hhn756.voidairrace.core.addons.usrpackage;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
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
     * 插件启用时执行：扫描用户包目录，解析其中的所有用户包（目录形式或 .zip 压缩形式），
     * 一次性交给包管理器按依赖拓扑排序批量加载<br>
     * 单个包加载失败（无论可预期失败还是意外失败）只记录日志，不影响其余包
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

        // 先收集全部包根（zip 挂载暂不关闭），批量交给包管理器统一排序加载
        List<PackageRoot> roots = new ArrayList<>();
        List<MountedZip> mounted = new ArrayList<>();
        for (Path entry : entries) {
            // 目录包：直接以该目录为包根
            if (Files.isDirectory(entry)) {
                roots.add(PackageRoot.ofDirectory(entry));
                continue;
            }
            // zip 包：挂载为只读文件系统，解析出包根，与目录包共用加载代码；其余文件忽略
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
            roots.add(PackageRoot.ofZip(rootInZip, entry));
            mounted.add(new MountedZip(entry, fs));
        }

        List<PackageManager.PackageLoadResult> results = packageManager.loadAll(roots);

        // 紧凑汇报：同类结果合并为一行，避免逐包刷屏；意外异常（含堆栈）仍逐条记录
        // 未启用与其他失败的条目以来源简称标识（失败结果不携带包 Id），已加载条目以包 Id 标识
        List<String> loadedIds = new ArrayList<>();
        List<String> notEnabledNames = new ArrayList<>();
        Map<String, List<String>> failureGroups = new LinkedHashMap<>(); // 失败键 → 各项技术性消息
        for (PackageManager.PackageLoadResult loadResult : results) {
            if (loadResult.exception() != null) {
                logger.log(Level.SEVERE, "自动加载用户包 " + loadResult.root().describe()
                        + " 时发生意外错误：" + loadResult.exception().getMessage(), loadResult.exception());
                continue;
            }
            ValueResult<UsrPackage> result = loadResult.result();
            if (result == null) continue; // 结果与异常必居其一（PackageLoadResult 契约），防御性跳过
            if (result.hasValue()) {
                loadedIds.add(result.value().id());
            } else if (result instanceof ValueResult.Failed<UsrPackage> failed) {
                if (TranslateKeys.Addons.USR_PACKAGE_PACKAGE_NOT_ENABLED.equals(failed.reasonKey())) {
                    notEnabledNames.add(shortSourceName(loadResult.root()));
                } else {
                    String techMessage = failed.techMessage();
                    failureGroups.computeIfAbsent(failed.reasonKey(), key -> new ArrayList<>())
                            .add(techMessage == null ? shortSourceName(loadResult.root()) : techMessage);
                }
            }
        }
        logger.info("已加载：" + (loadedIds.isEmpty() ? "无" : String.join("、", loadedIds)));
        if (!notEnabledNames.isEmpty()) {
            logger.warning("未加载未启用的包：" + String.join("、", notEnabledNames));
        }
        for (Map.Entry<String, List<String>> group : failureGroups.entrySet()) {
            logger.warning("未加载用户包（" + failureLabel(group.getKey()) + "）："
                    + String.join("；", group.getValue()));
        }

        // 加载成功的 zip 包保持挂载（包内容在本次启用中仍会被读取），其余挂载立即关闭避免泄漏
        for (MountedZip zip : mounted) {
            boolean loaded = results.stream().anyMatch(loadResult -> loadResult.isSuccess()
                    && loadResult.root().isZip()
                    && loadResult.root().zipPath().equals(zip.zipPath()));
            if (loaded) mountedZips.add(zip);
            else closeZip(zip.fileSystem(), zip.zipPath(), logger);
        }
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

    /** 失败原因翻译键 → 汇报行里的简短类别标签（未登记的键原样输出） */
    private static final @NonNull Map<@NonNull String, @NonNull String> FAILURE_LABELS = Map.ofEntries(
            Map.entry(TranslateKeys.Addons.USR_PACKAGE_DIR_NOT_FOUND, "包目录不存在"),
            Map.entry(TranslateKeys.Addons.USR_PACKAGE_NOT_A_PACKAGE, "缺少包元文件"),
            Map.entry(TranslateKeys.Addons.USR_PACKAGE_META_SIZE_ERROR, "元文件尺寸异常"),
            Map.entry(TranslateKeys.Addons.USR_PACKAGE_META_FIELD_FORMAT_ERROR, "元文件字段格式错误"),
            Map.entry(TranslateKeys.Addons.USR_PACKAGE_DEPENDENCY_CYCLE, "依赖存在循环"),
            Map.entry(TranslateKeys.Addons.USR_PACKAGE_ID_DUPLICATE, "包 Id 重复"),
            Map.entry(TranslateKeys.Addons.USR_PACKAGE_API_VERSION_UNSUPPORTED, "API 版本不受支持"),
            Map.entry(TranslateKeys.Addons.USR_PACKAGE_DEPENDENCY_MISSING, "依赖的包未加载"),
            Map.entry(TranslateKeys.Addons.USR_PACKAGE_ENTRY_SCRIPT_FAILED, "入口脚本执行失败")
    );

    /**
     * 取失败原因对应的简短类别标签
     *
     * @param reasonKey 失败结果的翻译键，允许为{@code null}（视为未知原因）
     *
     * @return 登记过的键返回对应标签，否则返回键原文；{@code null} 键返回「未知原因」
     * */
    private static @NonNull String failureLabel(@Nullable String reasonKey) {
        if (reasonKey == null) return "未知原因";
        return FAILURE_LABELS.getOrDefault(reasonKey, reasonKey);
    }

    /**
     * 取包来源的简称用于紧凑汇报：目录包为目录名，zip 包为 zip 文件名（不含上级路径）
     *
     * @param root 包根
     *
     * @return 来源简称
     * */
    private static @NonNull String shortSourceName(@NonNull PackageRoot root) {
        Path fileName = root.isZip() ? root.zipPath().getFileName() : root.path().getFileName();
        return String.valueOf(fileName);
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
