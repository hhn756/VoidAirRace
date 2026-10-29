package io.github.hhn756.voidairrace.infrastructure.language;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.hhn756.voidairrace.Voidairrace;
import io.github.hhn756.voidairrace.constants.ResourcePath;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.infrastructure.config.Config;
import io.github.hhn756.voidairrace.infrastructure.config.YamlConfig;
import io.github.hhn756.voidairrace.infrastructure.config.file.GlobalSettingKey;
import io.github.hhn756.voidairrace.infrastructure.config.file.PublicFile;
import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 服务端语言：读取插件内置资源包中的语言文件，按全局设置 {@code language} 字段提供的语言，
 * 把翻译键转换为服务端当前语言的文本
 * <p>
 * 定位是基础设施，不是业务服务：控制台日志、命令反馈、结果类型的技术性消息等无法依赖客户端翻译的场合用它；
 * 玩家可见文案由翻译键经客户端渲染，不经过本类
 * <p>
 * 翻译入口是静态方法：日志点分布在模块加载流程与配置管理器自身的代码里，语言表就绪之前就需要翻译，
 * 因此{@link #translate(String, Object...)}不依赖模块加载顺序。服务尚未初始化（或已随插件禁用卸载）时
 * 使用回退语言的语言表，任何时机的日志都能得到可读文本
 * <p>
 * 本类与{@link Config}相互引用——本类读取全局设置，配置管理器用本类输出日志。两者同属基础设施层，
 * 这个环只是基础设施内部的相互依赖，不代表基础设施可以反向依赖 service 层
 * */
public class ServerLanguage implements Module {
    /**
     * 内置资源包中语言文件所在目录
     * */
    private static final @NonNull String LANGUAGE_RESOURCE_DIR =
            ResourcePath.RESOURCEPACK + "assets/minecraft/lang/";

    /**
     * 配置值缺失或无法识别时使用的语言
     * */
    private static final @NonNull Language FALLBACK_LANGUAGE = Language.EN_US;

    /**
     * 本次插件启用中的服务实例；为{@code null} 表示服务未初始化或已卸载
     * */
    private static volatile @Nullable ServerLanguage instance;

    /**
     * 回退语言的语言表，首次需要时从内置资源加载<br>
     * 独立于服务实例：初始化之前与卸载之后都要能翻译
     * */
    private static volatile @Nullable Map<@NonNull String, @NonNull String> fallbackTexts;

    /**
     * 当前语言
     * */
    private @NonNull Language language = FALLBACK_LANGUAGE;

    /**
     * 当前语言的语言表
     * */
    private @NonNull Map<@NonNull String, @NonNull String> texts = Map.of();

    @Override
    public @NonNull Collection<@NonNull Class<? extends Module>> getRequiredModules() {
        return List.of(Config.class); // 读取全局设置前，配置管理器须已就绪
    }

    /**
     * 插件启用时执行：加载语言文件并解析全局设置中的语言字段
     * */
    private void onLoad(@NonNull Config config) {
        YamlConfig settings = config.getYmlConfig(PublicFile.GLOBAL_SETTING);
        // 按原始对象读取：字段写成非字符串时也要走「格式错误」回退，而不是类型转换异常
        Object configured = settings.get(GlobalSettingKey.LANGUAGE.path());
        Language parsed = configured instanceof String code ? Language.fromCode(code) : null;
        language = parsed == null ? FALLBACK_LANGUAGE : parsed;
        texts = loadTexts(language);
        instance = this;

        // 字段值格式错误：已回退到美式英语，警告按回退语言输出
        if (parsed == null) {
            logger().warning(translate(
                    TranslateKeys.Log.LANGUAGE_INVALID_SETTING,
                    relativeGlobalSettingPath(),
                    GlobalSettingKey.LANGUAGE.path(),
                    Language.supportedCodes()
            ));
        }
    }

    /**
     * 插件禁用时执行：丢弃本次启用中的语言表
     * */
    private void onUnload() {
        instance = null;
        texts = Map.of();
    }

    /**
     * 把翻译键转换为服务端当前语言的文本；当前语言缺少该键时依次回退到回退语言与键名本身
     *
     * @param key  翻译键
     * @param args 格式化参数，对应文本中的 {@code %s} 占位符；为空时原样返回文本
     *
     * @return 翻译后的文本
     * */
    public static @NonNull String translate(@NonNull String key, @Nullable Object... args) {
        ServerLanguage current = instance;
        String pattern = current == null ? null : current.texts.get(key);
        if (pattern == null) pattern = fallbackTexts().get(key);
        if (pattern == null) return key;

        if (args == null || args.length == 0) return pattern;
        return String.format(pattern, args);
    }

    /**
     * @return 当前语言
     * */
    public @NonNull Language getLanguage() {
        return language;
    }

    // ------ 内部辅助方法 ------

    /**
     * 从内置资源包读取指定语言的语言表
     *
     * @param language 目标语言
     *
     * @return 翻译键到文本的映射；资源缺失或解析失败时返回空表
     * */
    private static @NonNull Map<@NonNull String, @NonNull String> loadTexts(@NonNull Language language) {
        String path = LANGUAGE_RESOURCE_DIR + language.code() + ".json";
        try (InputStream stream = ServerLanguage.class.getResourceAsStream(path)) {
            if (stream == null) {
                logBundleFailure(path, null);
                return Map.of();
            }

            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)
            ).getAsJsonObject();

            Map<String, String> loaded = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive()) loaded.put(entry.getKey(), value.getAsString());
            }
            return Map.copyOf(loaded);
        } catch (IOException | RuntimeException e) {
            logBundleFailure(path, e);
            return Map.of();
        }
    }

    /**
     * @return 回退语言的语言表，首次调用时加载
     * */
    private static @NonNull Map<@NonNull String, @NonNull String> fallbackTexts() {
        Map<String, String> cached = fallbackTexts;
        if (cached != null) return cached;

        synchronized (ServerLanguage.class) {
            if (fallbackTexts == null) fallbackTexts = loadTexts(FALLBACK_LANGUAGE);
            return fallbackTexts;
        }
    }

    /**
     * 记录语言文件加载失败。此时语言表尚不可用，无法按语言输出，只能使用固定的英文文案
     * */
    private static void logBundleFailure(@NonNull String path, @Nullable Throwable cause) {
        Logger logger = pluginLogger();
        String message = "Failed to load bundled language file " + path;
        if (cause == null) {
            logger.severe(message);
        } else logger.log(Level.SEVERE, message, cause);
    }

    /**
     * @return 全局设置文件相对服务端根目录的路径（统一使用正斜杠）
     * */
    private static @NonNull String relativeGlobalSettingPath() {
        Path file = new File(
                Voidairrace.getInstance().getDataFolder(),
                PublicFile.GLOBAL_SETTING.filePath() + ".yml"
        ).toPath().toAbsolutePath();
        Path serverRoot = Path.of("").toAbsolutePath();
        try {
            return serverRoot.relativize(file).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return file.toString().replace('\\', '/');
        }
    }

    /**
     * 插件日志器；引导阶段主类实例尚不存在时退回同名 JUL 日志器，保证加载失败仍有记录
     * */
    private static @NonNull Logger pluginLogger() {
        try {
            return Voidairrace.getInstance().getLogger();
        } catch (NullPointerException e) {
            return Logger.getLogger("Voidairrace");
        }
    }

    private static @NonNull Logger logger() {
        return Voidairrace.getInstance().getLogger();
    }
}
