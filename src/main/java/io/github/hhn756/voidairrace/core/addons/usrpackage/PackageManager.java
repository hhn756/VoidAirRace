package io.github.hhn756.voidairrace.core.addons.usrpackage;

import io.github.hhn756.voidairrace.constants.Plugin;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.addons.TagRegistrar;
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
     * @param fieldName 字段名
     * @param type 字段值的类型
     * @param valueChecker 检查指定的、属于此字段的值实例是否合法（不用检查值类型）
     * @param required 是否必填；可选字段允许缺失（值为 null 时跳过类型与取值检查）
     * @param <V> 字段的值类型
     * */
    private record MetaField<V>(
            @NonNull String fieldName,
            @NonNull Class<V> type,
            @NonNull ValueChecker<V> valueChecker,
            boolean required
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
            @NonNull ValueChecker<V> valueChecker,
            boolean required
    ) {
        return new MetaField<>(fieldName, type, valueChecker, required);
    }

    /**
     * 定义用户包元文件中的所有字段（固定闭集）
     * */
    private static final MetaField<?>[] META_FIELDS = {
            field("id", String.class, PackageManager::checkId, true),
            field("version", Integer.class, PackageManager::checkVersion, true),
            field("entry", String.class, PackageManager::checkEntry, true),
            field("api", List.class, PackageManager::checkApi, true),
            field("dependencies", List.class, PackageManager::checkDependencies, false),
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

    private static @NonNull OperationResult checkDependencies(@NonNull List<?> dependencies) {
        for (int i = 0; i < dependencies.size(); i++) {
            Object dep = dependencies.get(i);
            // 依赖项是包 Id，加载后作为命名空间使用，字符集与包 Id 一致
            if (!(dep instanceof String depId) || !ID_PATTERN.matcher(depId).matches()) {
                return reason("第 " + (i + 1) + " 项必须是符合包 Id 格式的字符串（小写字母、数字、下划线和连字符）");
            }
        }
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
        // TagRegistrar：包脚本注册元素（规则的标签引用）时需要 TAG 类别已创建
        return List.of(PackageScriptService.class, Config.class, RuleRegistrar.class, TagRegistrar.class);
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

    /**
     * 单个包根的加载结果：成功、可预期失败与意外失败三者互斥
     *
     * @param root      参与加载的包根
     * @param result    加载结果；意外失败时为{@code null}
     * @param exception 意外失败（如读取元文件的 IO 异常）；非意外失败时为{@code null}
     * */
    public record PackageLoadResult(
            @NonNull PackageRoot root,
            @Nullable ValueResult<UsrPackage> result,
            @Nullable UsrPackageException exception
    ) {
        /**
         * @return 是否加载成功
         * */
        public boolean isSuccess() {
            return result != null && result.hasValue();
        }
    }

    private PackageManager() {}

    /**
     * 加载一批用户包：解析全部元文件，按依赖拓扑排序（环拒绝）后逐包执行完整加载<br>
     * 依赖声明指向本轮集合之外的包（包括本轮解析失败的包）时，在加载阶段以“依赖未加载”失败<br>
     * 单个包失败不影响其余包，全部结果逐项返回
     *
     * @param roots 待加载的包根集合
     *
     * @return 与输入顺序无关的逐包结果（按实际加载顺序排列）
     * */
    public @NonNull List<@NonNull PackageLoadResult> loadAll(@NonNull Collection<@NonNull PackageRoot> roots) {
        List<@NonNull PackageLoadResult> results = new ArrayList<>();

        // 阶段一：解析元文件（格式错误与 IO 异常只淘汰单个包，不中止整轮）
        List<@NonNull UsrPackage> parsed = new ArrayList<>();
        for (PackageRoot root : roots) {
            if (!Files.isDirectory(root.path())) {
                results.add(new PackageLoadResult(root, ValueResult.failure(
                        TranslateKeys.Addons.USR_PACKAGE_DIR_NOT_FOUND,
                        "无法加载用户包，包目录不存在：" + root.path()
                ), null));
                continue;
            }
            Path metaPath = root.path().resolve(META_FILE_NAME);
            if (!Files.isRegularFile(metaPath)) {
                results.add(new PackageLoadResult(root, ValueResult.failure(
                        TranslateKeys.Addons.USR_PACKAGE_NOT_A_PACKAGE,
                        "无法加载用户包，包元文件不存在：" + metaPath
                ), null));
                continue;
            }
            try {
                ValueResult<UsrPackage> parsedOne = parseMeta(metaPath, root);
                if (parsedOne.hasValue()) parsed.add(parsedOne.value());
                else results.add(new PackageLoadResult(root, parsedOne, null));
            } catch (UsrPackageException e) {
                results.add(new PackageLoadResult(root, null, e));
            }
        }

        // 阶段二：依赖环检测 + 拓扑排序（Kahn；被依赖者排前）。
        // 同 Id 重复包只让先出现者进图，落选者留到阶段三末尾按重复 Id 常规失败
        Map<@NonNull String, @NonNull UsrPackage> byId = new LinkedHashMap<>();
        for (UsrPackage pkg : parsed) byId.putIfAbsent(pkg.id(), pkg);

        // 入度 = 该包声明的、指向图内包的依赖数（去重；自依赖计入自身入度，自然落入环集合）
        Map<@NonNull String, @NonNull Integer> indegree = new HashMap<>();
        Map<@NonNull String, @NonNull List<@NonNull String>> dependents = new HashMap<>();
        for (UsrPackage pkg : byId.values()) {
            int degree = 0;
            for (String dep : new LinkedHashSet<>(pkg.dependencies())) {
                if (byId.containsKey(dep)) {
                    degree++;
                    dependents.computeIfAbsent(dep, key -> new ArrayList<>()).add(pkg.id());
                }
                // 指向图外的依赖边忽略：图外包不参与排序，加载阶段按“依赖未加载”失败
            }
            indegree.put(pkg.id(), degree);
        }

        Deque<@NonNull String> ready = new ArrayDeque<>();
        for (UsrPackage pkg : byId.values()) {
            if (indegree.get(pkg.id()) == 0) ready.add(pkg.id());
        }
        List<@NonNull UsrPackage> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            UsrPackage pkg = byId.get(ready.poll());
            ordered.add(pkg);
            for (String dependent : dependents.getOrDefault(pkg.id(), List.of())) {
                int degree = indegree.merge(dependent, -1, Integer::sum);
                if (degree == 0) ready.add(dependent);
            }
        }

        // 排序后仍有入度的包全部位于依赖环中
        for (UsrPackage pkg : byId.values()) {
            if (indegree.get(pkg.id()) > 0) results.add(new PackageLoadResult(pkg.root(), ValueResult.failure(
                    TranslateKeys.Addons.USR_PACKAGE_DEPENDENCY_CYCLE,
                    Component.text(pkg.id()),
                    "无法加载用户包 " + pkg.id() + "，依赖关系存在循环（滞留包集合："
                            + byId.values().stream().map(UsrPackage::id).filter(id -> indegree.get(id) > 0).toList() + "）",
                    null
            ), null));
        }

        // 阶段三：按拓扑序逐包加载；落选的重复 Id 包排在最后常规失败
        for (UsrPackage pkg : ordered) {
            results.add(new PackageLoadResult(pkg.root(), loadParsed(pkg), null));
        }
        for (UsrPackage pkg : parsed) {
            if (!byId.containsValue(pkg)) results.add(new PackageLoadResult(pkg.root(), loadParsed(pkg), null));
        }

        return results;
    }

    /**
     * 加载一个已解析的包：启用白名单 → 重复 Id → API 版本 → 依赖检查 → 执行入口脚本 → 登记<br>
     * 任一步失败即整包加载失败，不登记、不发布事件
     *
     * @param newPackage 已通过元文件解析的包
     *
     * @return 成功时携带新加载的包对象
     * */
    private @NonNull ValueResult<UsrPackage> loadParsed(@NonNull UsrPackage newPackage) {
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
                        + "（占用者来源：" + existing.describe() + "）",
                null
        );

        // API 版本约束：包声明的支持区间必须覆盖插件当前 API 版本
        if (!newPackage.supportsApiVersion(Plugin.API_VERSION)) return ValueResult.failure(
                TranslateKeys.Addons.USR_PACKAGE_API_VERSION_UNSUPPORTED,
                Component.text(newPackage.id()),
                "无法加载用户包，插件 API 版本不受支持：" + newPackage.id()
                        + "（要求 " + newPackage.api() + "，插件当前为 " + Plugin.API_VERSION + "）",
                null
        );

        // 依赖检查：声明的每个依赖包必须已加载（依赖方随被依赖方级联失败）
        for (String dep : newPackage.dependencies()) {
            if (!packages.containsKey(dep)) return ValueResult.failure(
                    TranslateKeys.Addons.USR_PACKAGE_DEPENDENCY_MISSING,
                    Component.text(dep),
                    "无法加载用户包 " + newPackage.id() + "，依赖的包未加载：" + dep,
                    null
            );
        }

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
        List<Integer> apiItems = List.copyOf((List<Integer>) rawMeta.get("api"));
        // 可选字段，缺失（键不存在或值为 null）视为无依赖；重复项留给拓扑排序去重
        @SuppressWarnings("unchecked")
        List<String> dependencies = rawMeta.get("dependencies") == null
                ? List.of()
                : List.copyOf((List<String>) rawMeta.get("dependencies"));

        return ValueResult.success(new UsrPackage(
                (String) rawMeta.get("id"),
                (Integer) rawMeta.get("version"),
                (String) rawMeta.get("entry"),
                new PackageApiVersion(apiItems.get(0), apiItems.get(1)),
                dependencies,
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
        // 检查未知字段（字段是固定闭集，出现闭集之外的字段即报错）
        List<String> unknownFields = rawMeta.keySet().stream()
                .filter(name -> Arrays.stream(META_FIELDS).noneMatch(f -> f.fieldName().equals(name)))
                .toList();
        if (!unknownFields.isEmpty()) {
            return OperationResult.failure(
                    TranslateKeys.Addons.USR_PACKAGE_META_SIZE_ERROR,
                    "元文件包含未知字段：" + unknownFields + "，允许的字段："
                            + Arrays.stream(META_FIELDS).map(MetaField::fieldName).toList()
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
     * 可选字段（{@code required=false}）允许缺失，缺失时跳过类型与取值检查；<br>
     * 检查器给出的原因只进技术性消息；字段名与用户文案在本方法统一组装
     * */
    private static <V> @NonNull OperationResult checkField(@NonNull MetaField<V> field, @Nullable Object rawValue) {
        // 不存在 或 值为空
        if (rawValue == null) {
            if (!field.required()) return ok();
            return fieldFormatError(field, "字段缺失或值为空");
        }
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
