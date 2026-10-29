package io.github.hhn756.voidairrace.addons.script.api.config;

import io.github.hhn756.voidairrace.Voidairrace;
import io.github.hhn756.voidairrace.addons.script.PackageAccess;
import io.github.hhn756.voidairrace.addons.usrpackage.UsrPackage;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.exception.ConfigException;
import io.github.hhn756.voidairrace.infrastructure.config.Config;
import io.github.hhn756.voidairrace.infrastructure.config.ConfigDefinition;
import io.github.hhn756.voidairrace.infrastructure.config.ConfigKey;
import io.github.hhn756.voidairrace.infrastructure.config.YamlConfig;
import io.github.hhn756.voidairrace.infrastructure.language.ServerLanguage;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.LuaRuntimeException;
import net.sandius.rembulan.Table;
import net.sandius.rembulan.runtime.ExecutionContext;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * 用户包配置服务：配置文件的路径裁定、schema 编译、yml 值与 Lua 值的双向转换，
 * 以及声明登记表的维护<br>
 * 配置存放在插件数据目录的独立子树中，与包资产（{@code /assets/}）分离；包卸载不清除配置，
 * 只有包主动调用删除函数才移除
 * <p>
 * 声明登记表按配置文件路径常驻：同一文件被重复声明时以最后一次为准
 * <p>
 * Lua 侧只暴露静态函数，不引入句柄；所有操作只在服务器主线程发生
 * */
public final class UserConfigService {
    /** 静态工具类，不可实例化 */
    private UserConfigService() {}

    /** 包配置在插件数据目录下的根目录 */
    private static final String CONFIG_ROOT = "custom_config";

    /**
     * 配置的文件 id：小写字母、数字、下划线、连字符，以{@code /}分段，不含点<br>
     * 扩展名由插件统一补{@code .yml}，禁止点在书写层面排除了{@code ..}形式的越界
     * */
    private static final Pattern FILE_ID = Pattern.compile("[a-z0-9_-]+(?:/[a-z0-9_-]+)*");

    /** schema 的最大嵌套深度 */
    private static final int MAX_SCHEMA_DEPTH = 32;

    /** schema 的最大节点数（含嵌套映射与序列项） */
    private static final int MAX_SCHEMA_NODES = 4096;

    /** 单个值表的最大嵌套深度 */
    private static final int MAX_VALUE_DEPTH = 32;

    /** 配置文件路径（不含扩展名）→ 该文件的键声明 */
    private static final Map<@NonNull String, @NonNull ConfigDefinition> declarations = new HashMap<>();

    /**
     * 配置访问目标
     *
     * @param pkg      配置所属的包
     * @param fileId   包内配置的文件 id（不含扩展名）
     * @param filePath 配置文件路径，相对插件数据目录且不含扩展名
     * */
    private record Target(@NonNull UsrPackage pkg, @NonNull String fileId, @NonNull String filePath) {}

    /**
     * 解析配置 id：{@code fileId}（本包）或{@code pkgid:fileId}（跨包，目标包须为调用方直接声明的依赖且已加载）<br>
     * 跨包结果只能用于读取
     *
     * @param caller   发起访问的包
     * @param spec     原始配置 id
     * @param resolver 包 Id → 已加载包对象的解析函数，查无此包返回{@code null}
     *
     * @return 配置访问目标
     * */
    private static @NonNull Target resolve(
            @NonNull UsrPackage caller,
            @NonNull String spec,
            @NonNull Function<@NonNull String, @Nullable UsrPackage> resolver
    ) {
        PackageAccess.Owner owner = PackageAccess.resolveOwner(caller, spec, resolver, "配置");
        if (!FILE_ID.matcher(owner.relPath()).matches()) {
            throw new LuaRuntimeException("配置 id 的文件部分非法（只允许小写字母、数字、下划线、连字符，"
                    + "以 / 分段，不含扩展名）：" + spec);
        }
        return new Target(
                owner.pkg(),
                owner.relPath(),
                CONFIG_ROOT + "/" + owner.pkg().id() + "/" + owner.relPath()
        );
    }

    /**
     * 解析本包配置的 id：只接受本包文件（可带自身包 Id 前缀），跨包 id 一律拒绝<br>
     * 供声明、写入、重载、删除使用；其他包的配置只读
     * */
    private static @NonNull Target resolveOwn(
            @NonNull UsrPackage caller,
            @NonNull String spec,
            @NonNull Function<@NonNull String, @Nullable UsrPackage> resolver
    ) {
        Target target = resolve(caller, spec, resolver);
        if (!target.pkg().id().equals(caller.id())) {
            throw new LuaRuntimeException("配置对其他包只读，不能声明或修改：" + spec);
        }
        return target;
    }

    /**
     * 声明一个配置文件的键与默认值：重复声明以最后一次为准，缺失的键补入默认值<br>
     * 声明会立即物化配置文件（文件不存在时生成默认值）
     *
     * @param caller   发起声明的包
     * @param spec     配置 id
     * @param schema   schema 表：键名 → 默认值或子 schema
     * @param resolver 包解析函数
     * */
    public static void declare(
            @NonNull UsrPackage caller,
            @NonNull String spec,
            @NonNull Table schema,
            @NonNull Function<@NonNull String, @Nullable UsrPackage> resolver
    ) {
        Target target = resolveOwn(caller, spec, resolver);

        List<ConfigKey<?>> keys = new ArrayList<>();
        compileSchema(schema, "", 0, keys, new int[]{0});
        ConfigDefinition definition = ConfigDefinition.external(
                target.filePath(), keys.toArray(new ConfigKey<?>[0]));

        declarations.put(target.filePath(), definition);
        Config.getInstance().evict(target.filePath());
        load(target, definition);
    }

    /**
     * 判断配置文件在磁盘上是否存在；跨包读取合法
     *
     * @param caller   发起查询的包
     * @param spec     配置 id
     * @param resolver 包解析函数
     *
     * @return 文件存在返回{@code true}
     * */
    public static boolean exists(
            @NonNull UsrPackage caller,
            @NonNull String spec,
            @NonNull Function<@NonNull String, @Nullable UsrPackage> resolver
    ) {
        return Files.isRegularFile(fileOf(resolve(caller, spec, resolver)));
    }

    /**
     * 读取配置快照：整份配置或指定路径的值；跨包读取合法
     *
     * @param context  Lua 执行上下文（构造返回表）
     * @param caller   发起读取的包
     * @param spec     配置 id
     * @param path     配置路径，{@code null} 或空串表示整份配置
     * @param resolver 包解析函数
     *
     * @return Lua 值；路径不存在、或路径中间段不是映射时返回{@code null}（= nil）
     * */
    public static @Nullable Object get(
            @NonNull ExecutionContext context,
            @NonNull UsrPackage caller,
            @NonNull String spec,
            @Nullable String path,
            @NonNull Function<@NonNull String, @Nullable UsrPackage> resolver
    ) {
        Target target = resolve(caller, spec, resolver);
        requireExists(target, spec);
        YamlConfig config = load(target, definitionOf(target));
        Object value = (path == null || path.isEmpty()) ? config : config.get(path);
        return toLua(context, value);
    }

    /**
     * 设置配置中指定路径的值并立即原子保存：仅本包可写<br>
     * 修改与普通配置一致地触发配置字段变更事件
     *
     * @param caller   发起写入的包
     * @param spec     配置 id
     * @param path     配置路径（点分隔）
     * @param value    新的 Lua 值
     * @param resolver 包解析函数
     * */
    public static void set(
            @NonNull UsrPackage caller,
            @NonNull String spec,
            @NonNull String path,
            @Nullable Object value,
            @NonNull Function<@NonNull String, @Nullable UsrPackage> resolver
    ) {
        Target target = resolveOwn(caller, spec, resolver);
        requireExists(target, spec);
        String keyPath = validateKeyPath(path, spec);

        Object javaValue = fromLua(
                value, "config.set 的参数（值）", 0, new int[]{0}, new IdentityHashMap<>());
        YamlConfig config = load(target, definitionOf(target));
        requireMapPath(config, keyPath, spec);
        config.set(keyPath, javaValue);
        try {
            config.saveAtomic();
        } catch (ConfigException e) {
            throw logAndThrow(
                    target, TranslateKeys.Log.PACKAGE_CONFIG_SAVE_FAILED, "保存配置失败：" + target.fileId(), e);
        }
        // 整段映射写入后重新加载：内存中的映射节点归一化为配置节，之后的点分路径读取才能继续向下
        if (javaValue instanceof Map<?, ?>) {
            Config.getInstance().evict(target.filePath());
            load(target, definitionOf(target));
        }
    }

    /**
     * 驱逐内存缓存并从磁盘重新加载：仅本包可调用
     *
     * @param caller   发起重载的包
     * @param spec     配置 id
     * @param resolver 包解析函数
     * */
    public static void reload(
            @NonNull UsrPackage caller,
            @NonNull String spec,
            @NonNull Function<@NonNull String, @Nullable UsrPackage> resolver
    ) {
        Target target = resolveOwn(caller, spec, resolver);
        requireExists(target, spec);
        Config.getInstance().evict(target.filePath());
        load(target, definitionOf(target));
    }

    /**
     * 删除配置文件、内存缓存与键声明：仅本包可调用
     *
     * @param caller   发起删除的包
     * @param spec     配置 id
     * @param resolver 包解析函数
     * */
    public static void delete(
            @NonNull UsrPackage caller,
            @NonNull String spec,
            @NonNull Function<@NonNull String, @Nullable UsrPackage> resolver
    ) {
        Target target = resolveOwn(caller, spec, resolver);

        try {
            Files.deleteIfExists(fileOf(target));
        } catch (IOException e) {
            throw logAndThrow(
                    target, TranslateKeys.Log.PACKAGE_CONFIG_DELETE_FAILED, "删除配置失败：" + target.fileId(), e);
        }
        Config.getInstance().evict(target.filePath());
        declarations.remove(target.filePath());
    }

    // ------ 内部辅助 ------

    /** 配置文件的实际路径（插件数据目录下的 yml 文件） */
    private static @NonNull Path fileOf(@NonNull Target target) {
        return new File(Voidairrace.getInstance().getDataFolder(), target.filePath() + ".yml").toPath();
    }

    /** 读取 / 写入 / 重载前置：配置文件必须已存在 */
    private static void requireExists(@NonNull Target target, @NonNull String spec) {
        if (!Files.isRegularFile(fileOf(target))) {
            throw new LuaRuntimeException("配置文件不存在：" + spec);
        }
    }

    /** 取该文件当前的键声明；未声明时用空定义读取，不产生键补齐 */
    private static @NonNull ConfigDefinition definitionOf(@NonNull Target target) {
        ConfigDefinition declared = declarations.get(target.filePath());
        if (declared != null) return declared;
        return ConfigDefinition.external(target.filePath(), new ConfigKey<?>[0]);
    }

    /** 加载配置；失败时把技术细节写入服务端日志，只向脚本抛出不含服务器路径的消息 */
    private static @NonNull YamlConfig load(@NonNull Target target, @NonNull ConfigDefinition definition) {
        try {
            return Config.getInstance().getYmlConfig(definition);
        } catch (ConfigException e) {
            throw logAndThrow(
                    target, TranslateKeys.Log.PACKAGE_CONFIG_LOAD_FAILED, "加载配置失败：" + target.fileId(), e);
        }
    }

    /** 记录带技术细节的服务端日志，并返回要抛给脚本的 Lua 错误 */
    private static @NonNull LuaRuntimeException logAndThrow(
            @NonNull Target target, @NonNull String logKey, @NonNull String luaMessage, @NonNull Exception cause) {
        Voidairrace.getInstance().getLogger().log(
                Level.SEVERE,
                ServerLanguage.translate(logKey, target.filePath()),
                cause
        );
        return new LuaRuntimeException(luaMessage);
    }

    /** 校验点分隔的配置路径：不允许空路径或空键段 */
    private static @NonNull String validateKeyPath(@NonNull String path, @NonNull String spec) {
        if (path.isEmpty()) {
            throw new LuaRuntimeException("配置路径不能为空：" + spec);
        }
        for (String segment : path.split("\\.", -1)) {
            if (segment.isEmpty()) {
                throw new LuaRuntimeException("配置路径不能有空键段：" + path + "（" + spec + "）");
            }
        }
        return path;
    }

    /** 写入前置：点分路径的每一段中间节点必须是映射（配置节或表）或不存在；标量 / 序列不能再向下写 */
    private static void requireMapPath(
            @NonNull YamlConfig config, @NonNull String keyPath, @NonNull String spec) {
        String[] segments = keyPath.split("\\.", -1);
        Object node = config;
        for (int i = 0; i < segments.length - 1; i++) {
            Object next;
            if (node instanceof ConfigurationSection section) {
                next = section.get(segments[i]);
            } else if (node instanceof Map<?, ?> map) {
                next = map.get(segments[i]);
            } else {
                next = null;
            }
            if (next == null) return;
            if (!(next instanceof ConfigurationSection) && !(next instanceof Map<?, ?>)) {
                throw new LuaRuntimeException(
                        "config.set：路径中间段不是映射：" + spec + " 的 " + segments[i]);
            }
            node = next;
        }
    }

    // ------ schema 编译 ------

    /**
     * 把 schema 表递归编译为键定义：叶子成为一个键，嵌套映射展开为点分路径，
     * 序列（键恰为 1..n）与空表整体成为一个键
     * */
    private static void compileSchema(
            @NonNull Table schema,
            @NonNull String prefix,
            int depth,
            @NonNull List<ConfigKey<?>> out,
            @NonNull int[] nodes
    ) {
        if (depth > MAX_SCHEMA_DEPTH) {
            throw new LuaRuntimeException("配置 schema 的嵌套深度超过上限 " + MAX_SCHEMA_DEPTH);
        }
        Object key = schema.initialKey();
        while (key != null) {
            if (!(key instanceof ByteString keyString)) {
                throw new LuaRuntimeException("配置 schema 的键名只允许字符串");
            }
            String name = keyString.decode();
            if (name.isEmpty() || name.contains(".")) {
                throw new LuaRuntimeException("配置 schema 的键名不能为空且不能包含 \".\"：" + name);
            }
            if (++nodes[0] > MAX_SCHEMA_NODES) {
                throw new LuaRuntimeException("配置 schema 的节点数超过上限 " + MAX_SCHEMA_NODES);
            }

            String path = prefix + name;
            Object value = schema.rawget(key);
            if (value instanceof Table table) {
                if (table.initialKey() == null) {
                    // 空表 = 空映射，整体作为一个键
                    out.add(ConfigKey.dynamic(path, new LinkedHashMap<String, Object>()));
                } else if (isSequence(table)) {
                    // 序列整体作为一个键，默认值是列表
                    out.add(ConfigKey.dynamic(path, fromLua(
                            table, "配置 schema 的键 “" + path + "”", depth + 1, nodes, new IdentityHashMap<>())));
                } else {
                    compileSchema(table, path + ".", depth + 1, out, nodes);
                }
            } else if (value instanceof Boolean || value instanceof Long || value instanceof Double
                    || value instanceof ByteString) {
                out.add(ConfigKey.dynamic(path, fromLua(
                        value, "配置 schema 的键 “" + path + "”", depth, nodes, new IdentityHashMap<>())));
            } else {
                throw new LuaRuntimeException(
                        "配置 schema 的键 “" + path + "” 的默认值只允许 boolean、number、string 或表");
            }
            key = schema.successorKeyOf(key);
        }
    }

    /** 判断表是否为序列：全部键都是正整数且恰好覆盖 1..n（空表视为非序列） */
    private static boolean isSequence(@NonNull Table table) {
        long count = 0;
        long max = 0;
        Object key = table.initialKey();
        while (key != null) {
            if (!(key instanceof Long index) || index < 1) return false;
            count++;
            if (index > max) max = index;
            key = table.successorKeyOf(key);
        }
        return count > 0 && count == max;
    }

    // ------ Lua 值 → Java 值 ------

    /**
     * 把 Lua 值转为 yml 可序列化的 Java 值：布尔 / 数字 / 字符串原样转，
     * 表按键的形状转为列表（键恰为 1..n）或字符串键映射，空表转为空映射
     * */
    private static @Nullable Object fromLua(
            @Nullable Object value,
            @NonNull String what,
            int depth,
            @NonNull int[] nodes,
            @NonNull IdentityHashMap<Table, Boolean> path
    ) {
        if (value == null) {
            throw new LuaRuntimeException(what + "不能为 nil");
        }
        if (depth > MAX_VALUE_DEPTH) {
            throw new LuaRuntimeException(what + "的嵌套深度超过上限 " + MAX_VALUE_DEPTH);
        }
        if (value instanceof Boolean || value instanceof Long || value instanceof Double) {
            return value;
        }
        if (value instanceof ByteString byteString) {
            return byteString.decode();
        }
        if (!(value instanceof Table table)) {
            throw new LuaRuntimeException(what + "只能是 boolean、number、string 或表");
        }
        if (++nodes[0] > MAX_SCHEMA_NODES) {
            throw new LuaRuntimeException(what + "的节点数超过上限 " + MAX_SCHEMA_NODES);
        }
        if (path.put(table, Boolean.TRUE) != null) {
            throw new LuaRuntimeException(what + "存在循环引用");
        }
        try {
            if (table.initialKey() == null) {
                return new LinkedHashMap<String, Object>();
            }
            if (isSequence(table)) {
                List<Object> list = new ArrayList<>();
                long index = 1;
                Object item;
                while ((item = table.rawget(index)) != null) {
                    list.add(fromLua(item, what + "的第 " + index + " 项", depth + 1, nodes, path));
                    index++;
                }
                return list;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            Object key = table.initialKey();
            while (key != null) {
                if (!(key instanceof ByteString keyString)) {
                    throw new LuaRuntimeException(what + "的键名只允许字符串");
                }
                String name = keyString.decode();
                map.put(name, fromLua(
                        table.rawget(key), what + "的字段 \"" + name + "\"", depth + 1, nodes, path));
                key = table.successorKeyOf(key);
            }
            return map;
        } finally {
            path.remove(table);
        }
    }

    // ------ Java 值 → Lua 值 ------

    /**
     * 把 yml 中的值转为 Lua 值：映射与配置节转为表，列表转为 1 起始索引表，
     * 其余基本类型按 Lua 的数种类型转换
     * */
    private static @Nullable Object toLua(@NonNull ExecutionContext context, @Nullable Object value) {
        if (value == null) return null;
        if (value instanceof Boolean) return value;
        if (value instanceof ByteString) return value;
        if (value instanceof Float floatValue) return floatValue.doubleValue();
        if (value instanceof Double) return value;
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String string) return ByteString.of(string);

        if (value instanceof ConfigurationSection section) {
            Table table = context.newTable();
            for (Map.Entry<String, Object> entry : section.getValues(false).entrySet()) {
                table.rawset(ByteString.of(entry.getKey()), toLua(context, entry.getValue()));
            }
            return table;
        }
        if (value instanceof Map<?, ?> map) {
            Table table = context.newTable();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                table.rawset(ByteString.of(String.valueOf(entry.getKey())), toLua(context, entry.getValue()));
            }
            return table;
        }
        if (value instanceof List<?> list) {
            Table table = context.newTable();
            long index = 1;
            for (Object item : list) {
                table.rawset(index++, toLua(context, item));
            }
            return table;
        }
        return ByteString.of(String.valueOf(value));
    }
}
