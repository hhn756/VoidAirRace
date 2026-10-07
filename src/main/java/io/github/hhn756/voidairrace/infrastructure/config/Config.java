package io.github.hhn756.voidairrace.infrastructure.config;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.exception.ConfigException;
import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 配置文件管理器，负责加载、保存和获取插件配置
 * 采用单例模式，通过 {@link #getInstance()} 获取实例
 */
public class Config implements Module {
    private static Config instance;

    @Override
    public Collection<Class<? extends Module>> getRequiredModules() {
        return List.of();
    }

    /** 插件启用时执行 */
    private void onLoad() {
        instance = this;
    }

    /** 插件禁用时执行：先把内存中的配置写回磁盘，再随模块系统卸载 */
    private void onUnload() {
        saveAll();
        instance = null;
    }

    /**
     * 获取配置管理器实例
     *
     * @return 配置管理器实例
     *
     * @throws NullPointerException 如果配置管理器实例不存在
     */
    public static @NonNull Config getInstance() throws NullPointerException {
        if (instance == null) throw new NullPointerException("配置管理器实例不存在");
        return instance;
    }

    // ------------

    /** 已加载的配置文件映射，键为配置文件名（无后缀），值为按定义对象中记录的实现类型加载的配置对象 */
    private final @NonNull HashMap<@NonNull String, @NonNull FileConfig> configs = new HashMap<>();

    /** 插件日志记录器 */
    private final @NonNull Logger logger;

    /**
     * 插件配置目录
     * */
    private final @NonNull File configFolder;

    /**
     * 私有构造器，初始化配置管理器
     */
    private Config() {
        VoidAirRace mainClass = VoidAirRace.getInstance();
        logger = mainClass.getLogger();
        configFolder = mainClass.getDataFolder();
    }

    /**
     * 将所有已加载的配置文件保存到磁盘（不会删除内存中的数据）<br>
     * 发生异常时每个文件最多重试3次
     * <p>
     * 这将遍历插件本次启用中所有已加载过的所有配置文件，对它们调用{@link Config#save(FileConfig, int)}（重试次数为{@code 3}）
     *
     * @throws ConfigException 如果有任何文件在重试后仍然保存失败
     */
    public void saveAll() throws ConfigException {
        int maxRetries = 3;
        for (Map.Entry<String, FileConfig> entry : configs.entrySet()) {
            FileConfig config = entry.getValue();
            save(config, maxRetries);
        }
    }

    /**
     * 尝试保存一个配置文件，支持重试
     * <p>
     * 这将调用{@code config}的{@link FileConfig#save()}
     *
     * @param config 要保存的、已加载到内存中的配置
     * @param maxRetries 最大重试次数
     *
     * @throws ConfigException 如果达到最大重试次数后仍然保存失败
     */
    public void save(@NonNull FileConfig config, int maxRetries) throws ConfigException {
        for (int i = 1; i <= maxRetries; i++) {
            try {
                config.save();
                return;
            } catch (ConfigException ignored) {}
        }
        logAndThrow(
                Level.SEVERE,
                "无法保存配置文件 '" + config.getDefine().filePath() + "' 总共尝试了 " + maxRetries + " 次都失败了"
        );
    }


    /**
     * 尝试保存一个配置文件，最多重试 {@code 3} 次
     *
     * @param config 要保存的、已加载到内存中的配置
     *
     * @throws ConfigException 如果达到最大重试次数后仍然保存失败
     *
     * @see Config#save(FileConfig, int)
     * */
    public void save(@NonNull FileConfig config) throws ConfigException {
        save(config, 3);
    }

    /**
     * 获取指定配置文件的可观察实例<br>
     * 如果配置文件尚未加载，则会从磁盘加载；若文件不存在，则由配置键定义物化生成默认配置文件<br>
     * 实例类型由定义对象中记录的实现类型（{@code IMPL}）决定，并据此分发到对应的加载器
     *
     * @param configDefinition 要读取的配置文件的定义对象
     *
     * @return 配置实例，类型为定义对象记录的实现类型
     *
     * @throws ConfigException 因各种原因获取配置失败时抛出
     */
    public <IMPL extends FileConfig> @NonNull IMPL getYmlConfig(@NonNull ConfigDefinition<IMPL> configDefinition)
            throws ConfigException {
        String definedPath = configDefinition.filePath();
        Class<IMPL> implClass = configDefinition.impl().getTypeClass();

        // 检查配置定义对象中的实现类型，分发到对应的加载器
        if (!implClass.equals(YamlConfig.class)) logAndThrow(
                Level.SEVERE,
                "配置文件 '" + definedPath + "' 请求的实现类型 '" + implClass.getName() + "' 没有对应的加载器");

        // 以下为 YamlConfig（目前唯一支持的实现类型）的加载逻辑
        File dataSourceFile = new File(configFolder, definedPath + ".yml");

        // 返回已加载的配置对象，防止平行配置对象导致状态混乱；减少IO次数
        FileConfig cacheValue = configs.get(definedPath);
        if (cacheValue != null) {
            if (!implClass.isInstance(cacheValue)) logAndThrow(
                    Level.SEVERE,
                    "配置文件 '" + definedPath + "' 已作为 '" + cacheValue.getClass().getName()
                    + "' 加载，与定义对象记录的实现类型 '" + implClass.getName() + "' 不符");
            return implClass.cast(cacheValue);
        }

        // 检查文件的上级目录是否存在
        File parentDir = dataSourceFile.getParentFile();
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) logAndThrow(
                Level.SEVERE,
                "无法创建插件配置目录: " + parentDir.getAbsolutePath());

        // 确保配置文件存在：由配置键定义物化生成（默认值的唯一真相源是键定义）
        if (!dataSourceFile.isFile()) {
            materializeDefaultFile(configDefinition, dataSourceFile);
        }

        // 检查文件是否可读
        if (!dataSourceFile.canRead()) logAndThrow(
                Level.SEVERE,
                "配置文件 '" + dataSourceFile.getAbsolutePath() + "' 无法读取，请检查权限");

        // 从文件加载配置数据
        return implClass.cast(loadYmlConfig(configDefinition, dataSourceFile));
    }

    /**
     * 内部辅助方法，用于加载一个yml配置文件
     *
     * @param configDefinition 要加载的文件的定义对象
     * @param dataSource       从指定文件读取配置数据
     * */
    private @NonNull YamlConfig loadYmlConfig(@NonNull ConfigDefinition<?> configDefinition, File dataSource)
            throws ConfigException {
        YamlConfig ymlConfig = new YamlConfig(configDefinition, dataSource);
        try {
            ymlConfig.load(dataSource);
        } catch (Exception e) {
            logAndThrow(Level.SEVERE, "无法加载配置文件 “" + configDefinition.filePath() + "”");
        }
        // 防止配置不全：为缺失键补默认值；发生补齐时保存并重载，使值类型转换与注释处理走与正常加载一致的路径
        if (fillMissingKeys(configDefinition, ymlConfig)) {
            try {
                ymlConfig.save(dataSource);
                ymlConfig.load(dataSource);
            } catch (Exception e) {
                logAndThrow(Level.SEVERE, "补齐配置文件 '" + configDefinition.filePath() + "' 缺失字段后保存或重载失败");
            }
        }

        // 记录
        configs.put(configDefinition.filePath(), ymlConfig);
        return ymlConfig;
    }

    /**
     * 内部辅助方法，记录指定等级日志然后抛出配置异常
     *
     * @param logLevel 日志等级
     * @param msg      日志和异常消息
     * */
    private void logAndThrow(@NonNull Level logLevel, @NonNull String msg) throws ConfigException {
        logger.log(logLevel, msg);
        throw new ConfigException(msg, null);
    }

    /**
     * 由配置键定义物化默认配置文件：逐键写入默认值与注释<br>
     * 默认值的唯一真相源是键定义（{@link ConfigKey}），不手工维护默认配置文件
     *
     * @param definition 配置文件的定义对象
     * @param target     目标文件（此时不存在）
     *
     * @throws ConfigException 写文件失败时抛出
     */
    private void materializeDefaultFile(@NonNull ConfigDefinition<?> definition, @NonNull File target)
            throws ConfigException {
        YamlConfiguration defaults = new YamlConfiguration();
        for (ConfigKey<?> key : definition.keys()) {
            Object def = key.defaultValue();
            if (def == null) continue;
            defaults.set(key.path(), def);
            if (key.comment() != null) defaults.setComments(key.path(), List.of(key.comment()));
        }
        try {
            defaults.save(target);
        } catch (IOException e) {
            String msg = "由配置键定义生成默认配置文件 '" + target.getAbsolutePath() + "' 失败";
            logger.log(Level.SEVERE, msg, e);
            throw new ConfigException(msg, e, null);
        }
    }

    /**
     * 为已存在的配置文件补充缺失的键：写入键的默认值与注释<br>
     * 用于插件升级后新增配置键的场景
     *
     * @param definition 配置文件的定义对象
     * @param target     已加载到内存的配置对象
     *
     * @return 是否发生过补齐；调用方应保存并重载配置
     * */
    private boolean fillMissingKeys(@NonNull ConfigDefinition<?> definition, @NonNull YamlConfig target) {
        boolean filled = false;
        for (ConfigKey<?> key : definition.keys()) {
            if (target.contains(key.path())) continue;
            Object def = key.defaultValue();
            if (def == null) continue;
            target.set(key.path(), def);
            if (key.comment() != null) target.setComments(key.path(), List.of(key.comment()));
            filled = true;
        }
        return filled;
    }
}
