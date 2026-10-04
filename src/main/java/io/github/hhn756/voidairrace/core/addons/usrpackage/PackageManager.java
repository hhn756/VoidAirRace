package io.github.hhn756.voidairrace.core.addons.usrpackage;

import io.github.hhn756.voidairrace.constants.Plugin;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.addons.usrpackage.script.PackageScriptService;
import io.github.hhn756.voidairrace.core.matchrule.RuleRegistrar;
import io.github.hhn756.voidairrace.event.UsrPackageLoadEvent;
import io.github.hhn756.voidairrace.exception.UsrPackageException;
import io.github.hhn756.voidairrace.infrastructure.config.Config;
import io.github.hhn756.voidairrace.infrastructure.config.files.GameSettingKeys;
import io.github.hhn756.voidairrace.infrastructure.config.files.PublicFiles;
import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import io.github.hhn756.voidairrace.result.OperationResult;
import io.github.hhn756.voidairrace.result.ValueResult;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 记录和管理用户包的加载、卸载
 * */
public class PackageManager implements Module {
    /**
     * 用户包元文件的名称（含后缀名）
     * */
    static final String META_FILE_NAME = "pack.varmeta";

    /**
     * 包 Id 的合法字符集：小写字母、数字、下划线、连字符（Id 作为命名空间使用，字符集从严）
     * */
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_-]+");

    /**
     * 内部辅助类，代表用户包元文件中的一个字段
     *
     * @param <V> 字段的值类型
     * */
    private record MetaField<V>(
            @NonNull String fieldName,
            @NonNull Class<V> type,
            @NonNull ValueChecker<V> valueChecker
    ) {}

    /**
     * 字段值检查器，负责类型之外的取值约束（格式、语义）<br>
     * 检查通过返回{@link OperationResult#success()}；不通过返回键为{@code null}、
     * 只携带技术性原因的失败（{@link PackageManager#reason(String)}）——
     * 用户文案统一由“字段格式错误”键表达，具体原因只进技术性消息
     *
     * @param <V> 字段的值类型
     * */
    private interface ValueChecker<V> {
        /**
         * 检查此字段的指定取值是否合理
         *
         * @param value 指定值
         * */
        @NonNull OperationResult check(@NonNull V value);
    }

    /**
     * {@link MetaField} 的构造工厂：仅凭 {@code Class<V>} 把值类型 V 与检查器钉成同一类型，
     * 使调用处的检查器参数获得具体类型（数组字面量无法携带泛型，不能直接 {@code new MetaField<>[]}）
     * */
    private static <V> @NonNull MetaField<V> field(
            @NonNull String fieldName,
            @NonNull Class<V> type,
            @NonNull ValueChecker<V> valueChecker
    ) {
        return new MetaField<>(fieldName, type, valueChecker);
    }

    /**
     * 定义用户包元文件中的所有字段（固定闭集）
     * */
    private static final MetaField<?>[] META_FIELDS = {
            field("id", String.class, PackageManager::checkId),
            field("version", Integer.class, PackageManager::checkVersion),
            field("entry", String.class, PackageManager::checkEntry),
            field("api", List.class, PackageManager::checkApi),
    };

    // ------ 字段值检查器 ------

    private static @NonNull OperationResult checkId(@NonNull String id) {
        if (!ID_PATTERN.matcher(id).matches()) return reason("只允许小写字母、数字、下划线和连字符");
        return ok();
    }

    private static @NonNull OperationResult checkVersion(@NonNull Integer version) {
        if (version < 1) return reason("必须是正整数");
        return ok();
    }

    private static @NonNull OperationResult checkEntry(@NonNull String entry) {
        if (entry.isBlank()) return reason("不能为空");
        if (entry.startsWith("/") || entry.startsWith("\\")) return reason("必须是包内相对路径");
        if (entry.contains("..")) return reason("不允许包含 \"..\"");
        if (entry.endsWith("/") || entry.endsWith("\\")) return reason("必须指向脚本文件而不是目录");
        return ok();
    }

    private static @NonNull OperationResult checkApi(@NonNull List<?> api) {
        if (api.size() != 2) return reason("必须恰好两项（最小与最大 API 版本号）");
        if (!(api.get(0) instanceof Integer min) || !(api.get(1) instanceof Integer max)) return reason("每一项都必须是整数");
        if (min < 1 || max < 1) return reason("版本号必须是正整数");
        if (min > max) return reason("最小版本号不能大于最大版本号");
        return ok();
    }

    /**
     * 检查器取值通过
     * */
    private static @NonNull OperationResult ok() {
        return OperationResult.success();
    }

    /**
     * 检查器取值不通过，给出仅进技术性消息的原因
     * */
    private static @NonNull OperationResult reason(@NonNull String reason) {
        return OperationResult.failure(null, reason);
    }

    // ------

    private static @Nullable PackageManager instance;

    /** 脚本运行时，onLoad 注入；入口脚本的编译与执行都经它 */
    private @Nullable PackageScriptService scriptService;

    @Override
    public Collection<Class<? extends Module>> getRequiredModules() {
        // RuleRegistrar：包脚本注册规则时需要 RULE 类别已创建
        return List.of(PackageScriptService.class, Config.class, RuleRegistrar.class);
    }

    /** 插件启用时执行 */
    private void onLoad(@NonNull PackageScriptService scriptService) {
        instance = this;
        this.scriptService = scriptService;
    }

    /** 插件禁用时执行 */
    private void onUnload() {
        instance = null;
        scriptService = null;
    }

    public static @NonNull PackageManager getInstance() throws NullPointerException {
        if (instance == null) throw new NullPointerException("用户包管理器实例不存在");
        return instance;
    }

    // ------

    /**
     * 已加载的所有包，键为包名；值为包记录
     * */
    private final @NonNull Map<@NonNull String, @NonNull UsrPackage> packages = new HashMap<>();

    private PackageManager() {}

    /**
     * 加载一个用户包
     *
     * @param packageRoot 包根（目录形式或 zip 挂载形式，由调用方解析构造）
     *
     * @return 成功时携带新加载的包对象
     *
     * @throws UsrPackageException 读取元文件发生 IO 异常时抛出（意外失败），由调用方按意外失败处理
     * */
    public @NonNull ValueResult<UsrPackage> load(@NonNull PackageRoot packageRoot) throws UsrPackageException {
        Path packagePath = packageRoot.path();
        // 如果目录不存在（目录路径属于服务端参数，只进技术性消息）
        if (!Files.isDirectory(packagePath)) return ValueResult.failure(
                TranslateKeys.Addons.USR_PACKAGE_DIR_NOT_FOUND,
                "无法加载用户包，包目录不存在：" + packagePath
        );
        // 如果目录不是用户包
        Path metaPath = packagePath.resolve(META_FILE_NAME);
        if (!Files.isRegularFile(metaPath)) return ValueResult.failure(
                TranslateKeys.Addons.USR_PACKAGE_NOT_A_PACKAGE,
                "无法加载用户包，包元文件不存在：" + metaPath
        );

        // 解析元数据（格式错误属可预期失败，原样向上传递）
        ValueResult<UsrPackage> parsed = parseMeta(metaPath, packageRoot);
        if (!parsed.hasValue()) return parsed;
        UsrPackage newPackage = parsed.value();

        // 启用白名单：未列入 game_settings 的 enable_packages 的包不加载（在执行入口脚本之前拒绝）
        List<String> enabledPackages = Config.getInstance()
                .getYmlConfig(PublicFiles.GAME_SETTINGS)
                .get(GameSettingKeys.ENABLE_PACKAGES, List.of());
        if (!enabledPackages.contains(newPackage.id())) return ValueResult.failure(
                TranslateKeys.Addons.USR_PACKAGE_PACKAGE_NOT_ENABLED,
                Component.text(newPackage.id()),
                "无法加载用户包，未在 enable_packages 中启用：" + newPackage.id()
                        + "（当前启用列表：" + enabledPackages + "）",
                null
        );

        // 防撞车：重复 Id 不可加载
        UsrPackage existing = packages.get(newPackage.id());
        if (existing != null) return ValueResult.failure(
                TranslateKeys.Addons.USR_PACKAGE_ID_DUPLICATE,
                Component.text(newPackage.id()),
                "无法加载用户包，Id 已被占用：" + newPackage.id()
                        + "（占用者来源：" + existing.root().describe() + "）",
                null
        );

        // API 版本约束：包声明的 [最小, 最大] 区间必须覆盖插件当前 API 版本
        List<Integer> api = newPackage.api();
        if (Plugin.API_VERSION < api.get(0) || Plugin.API_VERSION > api.get(1)) return ValueResult.failure(
                TranslateKeys.Addons.USR_PACKAGE_API_VERSION_UNSUPPORTED,
                Component.text(newPackage.id()),
                "无法加载用户包，插件 API 版本不受支持：" + newPackage.id()
                        + "（要求 [" + api.get(0) + ", " + api.get(1) + "]，插件当前为 " + Plugin.API_VERSION + "）",
                null
        );

        // 执行包入口脚本：失败即整包加载失败，不登记、不发病事件（脚本侧已自行回滚）
        ValueResult<Object[]> entry = scriptService.executeEntry(newPackage, this::lookupLoadedPackage);
        if (!entry.hasValue()) return ValueResult.fromOperation(entry.toOperation());

        // 记录
        packages.put(newPackage.id(), newPackage);

        // 发布插件内事件
        new UsrPackageLoadEvent(newPackage).callEvent();

        return ValueResult.success(newPackage);
    }

    /**
     * 卸载一个用户包
     *
     * @param packageName 要卸载的包的Id
     *
     * @return 如果指定的包已加载并成功卸载返回{@code true}；包未加载返回{@code false}
     * */
    public boolean unload(@NonNull String packageName) {
        UsrPackage removed = packages.remove(packageName);
        if (removed == null) return false;

        // TODO 通知包逻辑停止运行（等脚本执行实现后）
        return true;
    }

    /**
     * 检查指定用户包是否已加载
     *
     * @param packageName 指定包名
     *
     * @return 如果指定包名的用户包已加载将返回{@code true}，否则返回{@code false}
     * */
    public boolean isLoaded(@NonNull String packageName) {
        return packages.containsKey(packageName);
    }

    /**
     * 获取指定Id的包对象，前提是它已加载
     *
     * @param packageName 指定包的包名
     *
     * @return 指定包对象
     *
     * @throws NullPointerException 如果指定包未加载
     * */
    public @NonNull UsrPackage get(@NonNull String packageName) {
        UsrPackage p = packages.get(packageName);
        if (p == null) throw new NullPointerException("无法获取未加载的包：“" + packageName + "”");
        return p;
    }

    /**
     * 列出所有已加载的用户包
     *
     * @return 所有已加载的包构成的列表
     * */
    public Collection<@NonNull UsrPackage> list() {
        return Collections.unmodifiableCollection(packages.values());
    }

    // ------ 内部方法 ------

    /**
     * 查找一个已加载的包（供脚本运行时跨包解析 require 引用）
     *
     * @param packageId 包 Id
     *
     * @return 已加载的包对象，未加载返回{@code null}
     * */
    private @Nullable UsrPackage lookupLoadedPackage(@NonNull String packageId) {
        return packages.get(packageId);
    }

    /**
     * 解析一个用户包元文件
     *
     * @param metaPath 元文件路径
     * @param packageRoot 元文件所属的包根
     *
     * @return 成功时携带根据元数据生成的包对象
     *
     * @throws UsrPackageException 读取元文件发生 IO 异常时抛出（意外失败），由调用方按意外失败处理
     * */
    private static @NonNull ValueResult<UsrPackage> parseMeta(
            @NonNull Path metaPath,
            @NonNull PackageRoot packageRoot
    ) throws UsrPackageException {
        Object loaded;
        try (InputStream stream = Files.newInputStream(metaPath)) {
            loaded = new Yaml().load(stream);
        } catch (IOException e) {
            throw new UsrPackageException(
                    "读取包元文件时发生了 IO 异常：" + metaPath,
                    Component.translatable(TranslateKeys.Addons.USR_PACKAGE_META_IO_EXCEPTION)
            );
        }

        // 顶层不是映射（含空文件）视作没有字段，交给字段数量检查报错
        Map<String, @Nullable Object> rawMeta;
        if (loaded instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, @Nullable Object> cast = (Map<String, @Nullable Object>) map;
            rawMeta = cast;
        } else {
            rawMeta = Map.of();
        }

        // 格式不对就报错
        OperationResult check = checkMeta(rawMeta);
        if (!check.isSuccess()) return ValueResult.fromOperation(check);

        // 字段类型与取值已在检查中验证，此处的强转必然安全
        @SuppressWarnings("unchecked")
        List<Integer> api = List.copyOf((List<Integer>) rawMeta.get("api"));

        return ValueResult.success(new UsrPackage(
                (String) rawMeta.get("id"),
                (Integer) rawMeta.get("version"),
                (String) rawMeta.get("entry"),
                api,
                packageRoot // 元文件一定在包根目录，包根由调用方解析构造
        ));
    }

    /**
     * 检查一个用户包元文件的格式（字段闭集、字段类型、取值语义）
     *
     * @param rawMeta 由{@link Yaml#load(InputStream)}加载的包元文件
     *
     * @return 格式合法返回成功；否则返回携带“字段格式错误”或“字段数量错误”键的失败
     * */
    private static @NonNull OperationResult checkMeta(@NonNull Map<String, @Nullable Object> rawMeta) {
        // 检查字段数量
        if (rawMeta.size() != META_FIELDS.length) {
            return OperationResult.failure(
                    TranslateKeys.Addons.USR_PACKAGE_META_SIZE_ERROR,
                    "元文件字段数量错误，预期 " + META_FIELDS.length + "，实际 " + rawMeta.size() + "，实际字段：" + rawMeta.keySet()
            );
        }

        // 检查每个字段
        for (MetaField<?> metaField : META_FIELDS) {
            OperationResult result = checkField(metaField, rawMeta.get(metaField.fieldName()));
            if (!result.isSuccess()) return result;
        }
        return ok();
    }

    /**
     * 检查元文件中单个字段的缺失、类型与取值语义<br>
     * 检查器给出的原因只进技术性消息；字段名与用户文案在本方法统一组装
     * */
    private static <V> @NonNull OperationResult checkField(@NonNull MetaField<V> field, @Nullable Object rawValue) {
        // 不存在 或 值为空
        if (rawValue == null) return fieldFormatError(field, "字段缺失或值为空");
        // 类型
        if (!field.type().isInstance(rawValue)) {
            return fieldFormatError(field, "值类型为 " + rawValue.getClass().getSimpleName()
                    + "，预期 " + field.type().getSimpleName());
        }
        // 值语义
        OperationResult result = field.valueChecker().check(field.type().cast(rawValue));
        return result instanceof OperationResult.Failed failed
                ? fieldFormatError(field, failed.techDetail())
                : result;
    }

    /**
     * 构造“字段格式错误”的失败结果：字段名（插件自定义的固定闭集，非服务端参数）
     * 作为翻译键的参数进入用户消息，具体原因只进技术性消息
     * */
    private static @NonNull OperationResult fieldFormatError(@NonNull MetaField<?> field, @Nullable String reason) {
        return OperationResult.failure(
                TranslateKeys.Addons.USR_PACKAGE_META_FIELD_FORMAT_ERROR,
                Component.text(field.fieldName()),
                "字段 “" + field.fieldName() + "” 格式错误" + (reason == null ? "" : "：" + reason),
                null
        );
    }
}
