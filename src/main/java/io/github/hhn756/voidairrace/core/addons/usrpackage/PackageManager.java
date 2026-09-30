package io.github.hhn756.voidairrace.core.addons.usrpackage;

import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.event.UsrPackageLoadEvent;
import io.github.hhn756.voidairrace.exception.UsrPackageException;
import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import io.github.hhn756.voidairrace.result.ValueResult;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 记录和管理用户包的加载、卸载
 * */
public class PackageManager implements Module {
    private static final String PACKAGE_META = "pack.varmeta";
    /** 内部辅助类，代表用户包元文件中的一个字段 */
    private record MetaField(@NonNull String fieldName, @NonNull Class<?> type) {}
    /** 定义用户包元文件中的所有字段 */
    private static final MetaField[] META_FIELDS = new MetaField[]{
            new MetaField("id", String.class),
            new MetaField("entryPoint", String.class),
    };

    private static @Nullable PackageManager instance;

    @Override
    public Collection<Class<? extends Module>> getRequiredModules() {
        return List.of();
    }

    /** 插件启用时执行 */
    private void onLoad() {
        instance = this;
    }

    /** 插件停用时执行 */
    private void onUnload() {
        instance = null;
    }

    public static @NonNull PackageManager getInstance() throws NullPointerException {
        if (instance == null) throw new NullPointerException("用户包管理器实例不存在");
        return instance;
    }

    // ------

    /**
     * 已加载的所有包，键为包名；值为包记录
     * */
    private final @NonNull Map<@NonNull String,@NonNull UsrPackage> packages = new HashMap<>();

    private PackageManager() {}

    /**
     * 加载一个用户包
     *
     * @param packagePath 用户包根目录路径
     * */
    public @NonNull ValueResult<UsrPackage> load(@NonNull Path packagePath) {
        // 如果目录不存在（目录路径属于服务端参数，只进技术性消息）
        if (!packagePath.toFile().exists()) return ValueResult.failure(
                TranslateKeys.Addons.USR_PACKAGE_DIR_NOT_FOUND,
                "包目录不存在：" + packagePath
        );
        // 如果目录不是包
        Path metaPath = packagePath.resolve(PACKAGE_META);
        if (!metaPath.toFile().exists()) return ValueResult.failure(
                TranslateKeys.Addons.USR_PACKAGE_NOT_A_PACKAGE,
                "包元文件不存在：" + metaPath
        );

        // 解析元数据
        UsrPackage newPackage = parseMeta(metaPath);

        // 记录
        packages.put(newPackage.id(), newPackage);

        // 发布插件内事件
        new UsrPackageLoadEvent(newPackage).callEvent();

        // 执行包入口函数
        // TODO ^

        return ValueResult.success(newPackage);
    }

    /**
     * 卸载一个用户包
     *
     * @param packageName 要卸载的包的Id
     * */
    public void unload(@NonNull String packageName) {

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
     * 解析一个用户包元文件
     *
     * @return 根据元数据生成的包对象
     * */
    private @NonNull UsrPackage parseMeta(@NonNull Path metaPath) throws UsrPackageException {
        // 解析yml
        Map<@NonNull String, @Nullable Object> rawMeta = new HashMap<>();
        try {
            new Yaml().load(Files.newInputStream(metaPath));
        } catch (IOException e) {
            throw new UsrPackageException(
                    "读取包元文件时发生了 IO 异常",
                    Component.translatable(TranslateKeys.Addons.USR_PACKAGE_META_IO_EXCEPTION));
        }

        // 检查字段格式
        for (MetaField metaField : META_FIELDS) {
            Object value = rawMeta.get(metaField.fieldName);
            // 如果字段不存在 或 值为空
            if (value == null) fieldFormatError(metaField.fieldName);
            // 如果类型错误
            if (!metaField.type.isAssignableFrom(value.getClass())) fieldFormatError(metaField.fieldName);
        }

        // 构造包对象
        return new UsrPackage(
                (String) rawMeta.get("id"),
                (String) rawMeta.get("entryPoint"),
                metaPath.getParent()
        );
    }

    private void fieldFormatError(@NonNull String fieldName) throws UsrPackageException {
        throw new UsrPackageException(
                "字段 “" + fieldName + "” 格式错误",
                Component.translatable(TranslateKeys.Addons.USR_PACKAGE_META_FIELD_FORMAT_ERROR)
                        .arguments(Component.text(fieldName))
        );
    }
}
