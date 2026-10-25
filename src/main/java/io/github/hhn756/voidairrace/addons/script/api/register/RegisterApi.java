package io.github.hhn756.voidairrace.addons.script.api.register;

import io.github.hhn756.voidairrace.Voidairrace;
import io.github.hhn756.voidairrace.addons.GameElementMeta;
import io.github.hhn756.voidairrace.addons.TagEntry;
import io.github.hhn756.voidairrace.addons.script.ScriptCallGate;
import io.github.hhn756.voidairrace.addons.script.TextComponents;
import io.github.hhn756.voidairrace.addons.usrpackage.UsrPackage;
import io.github.hhn756.voidairrace.constants.Categories;
import io.github.hhn756.voidairrace.constants.Plugin;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.map.MapEntry;
import io.github.hhn756.voidairrace.core.matchrule.RuleEntry;
import io.github.hhn756.voidairrace.exception.RegistryException;
import io.github.hhn756.voidairrace.infrastructure.registry.DefaultSubtable;
import io.github.hhn756.voidairrace.infrastructure.registry.Registry;
import io.github.hhn756.voidairrace.result.OperationResult;
import io.github.hhn756.voidairrace.service.language.ServerLanguageService;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.LuaRuntimeException;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.Table;
import net.sandius.rembulan.impl.NonsuspendableFunctionException;
import net.sandius.rembulan.load.ChunkClassLoader;
import net.sandius.rembulan.runtime.AbstractFunction1;
import net.sandius.rembulan.runtime.ExecutionContext;
import net.sandius.rembulan.runtime.LuaFunction;
import org.bukkit.NamespacedKey;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 元素注册类别的 Lua API 入口：包环境（{@code _G}）全局表{@code register}下的
 * {@code map} / {@code rule} / {@code tag} 三个类别注册函数<br>
 * 向注册表注册用户包定义的游戏元素，并承载元素注册的全部解析与校验逻辑；
 * 校验失败抛 Lua 错误，使整包加载失败
 * （由{@link ScriptCallGate}的异常映射折入失败通道）
 * <p>
 * 本类是类别入口：装配期由{@code PluginApi}调用{@link #installInto}注入 API 表
 * */
public final class RegisterApi {
    /** 静态工具类，不可实例化 */
    private RegisterApi() {}

    /**
     * 把本类别的 API 函数注入包脚本环境：建全局表{@code register}并注入三个
     * 类别注册函数（{@code map} / {@code rule} / {@code tag}）
     *
     * @param stateContext     共享 Lua 状态上下文（建表与注册规则时构造回调用）
     * @param chunkClassLoader 编译产物类加载器（注册规则时构造规则回调时传给适配器）
     * @param pkg              环境所属的包
     * @param env              包脚本环境（{@code _G}），全局表装入目标
     * */
    public static void installInto(
            @NonNull StateContext stateContext,
            @NonNull ChunkClassLoader chunkClassLoader,
            @NonNull UsrPackage pkg,
            @NonNull Table env
    ) {
        Table register = stateContext.newTable();
        register.rawset(ByteString.of("map"), new RegisterFunction(stateContext, chunkClassLoader, pkg, "map"));
        register.rawset(ByteString.of("rule"), new RegisterFunction(stateContext, chunkClassLoader, pkg, "rule"));
        register.rawset(ByteString.of("tag"), new RegisterFunction(stateContext, chunkClassLoader, pkg, "tag"));
        env.rawset(ByteString.of("register"), register);
    }

    // ------ 元素注册 ------

    /**
     * 元素键名的合法字符集：小写字母、数字、下划线、连字符<br>
     * 键名经包 Id 前缀组成 NamespacedKey，字符集从严
     * */
    private static final Pattern ELEMENT_KEY_PATTERN = Pattern.compile("[a-z0-9_-]+");

    /**
     * 处理{@code register("map", descriptor)}：解析并校验地图描述符，
     * 构造地图注册项登记到注册表<br>
     * 用户地图均为可游玩地图（本版本不开放不可游玩形态）
     *
     * @param stateContext     共享 Lua 状态上下文（构造地图实例时传入适配器）
     * @param chunkClassLoader 编译产物类加载器（构造地图实例时传入适配器）
     * @param pkg              发起注册的包
     * @param descriptor       地图描述符表
     * */
    private static void registerMap(
            @NonNull StateContext stateContext,
            @NonNull ChunkClassLoader chunkClassLoader,
            @NonNull UsrPackage pkg,
            @NonNull Table descriptor
    ) {
        // id：只写键名，自动加包前缀（与规则/标签同规则）
        String keyName = optString(descriptor, "id", "地图");
        if (keyName == null) throw new LuaRuntimeException("地图描述符的字段 “id” 必填");
        if (!ELEMENT_KEY_PATTERN.matcher(keyName).matches()) {
            throw new LuaRuntimeException("地图描述符的字段 “id” 只允许小写字母、数字、下划线和连字符：" + keyName);
        }
        NamespacedKey mapId;
        try {
            mapId = new NamespacedKey(pkg.id(), keyName);
        } catch (IllegalArgumentException e) {
            throw new LuaRuntimeException("地图描述符的字段 “id” 非法：" + keyName + "（" + e.getMessage() + "）");
        }

        // 标签（可选）：与规则同规则
        List<@NonNull String> rawTags = optStringList(descriptor, "tags", "地图");
        List<@NonNull NamespacedKey> tags = resolveTags(pkg, rawTags == null ? List.of() : rawTags, "地图");

        // 最大队伍数量（必填）：用户地图均为可游玩地图，可游玩地图必须给出
        Long maxTeams = optPositiveLong(descriptor, "max_teams", "地图");
        if (maxTeams == null) throw new LuaRuntimeException("地图描述符的字段 “max_teams” 必填（正整数）");

        // 行为回调（全部可选；未提供时开始/结束直接成功）
        LuaFunction onStart = optFunction(descriptor, "on_start", "地图");
        LuaFunction onOver = optFunction(descriptor, "on_over", "地图");

        // 登记：entry() 是构造注册项的唯一入口，工厂在每次实例化时产出全新的 LuaGameMap
        DefaultSubtable<MapEntry, NamespacedKey> subtable = Registry.getInstance().category(Categories.MAP);
        GameElementMeta meta = parseElementMeta(mapId, descriptor, tags, "地图");
        OperationResult addResult;
        try {
            addResult = subtable.add(LuaGameMap.entry(
                    meta,
                    Math.toIntExact(maxTeams),
                    onStart,
                    onOver,
                    stateContext,
                    chunkClassLoader,
                    pkg,
                    pkg.id() + ":" + keyName
            ));
        } catch (RegistryException e) {
            // 注册项校验失败（本路径仅可能因约定冲突触发），转为整包加载失败
            throw new LuaRuntimeException("地图注册失败：" + e.getMessage());
        }
        if (!addResult.isSuccess()) {
            throw new LuaRuntimeException("地图 id 已被注册：" + mapId);
        }
        Voidairrace.getInstance().getLogger().info(ServerLanguageService.translate(
                TranslateKeys.Log.REGISTER_API_MAP_REGISTERED, pkg.id(), mapId));
    }

    /**
     * 处理{@code register("rule", descriptor)}：解析并校验规则描述符，
     * 构造用户规则注册项登记到注册表
     *
     * @param stateContext     共享 Lua 状态上下文（构造规则回调时传入适配器）
     * @param chunkClassLoader 编译产物类加载器（注册规则时传入适配器）
     * @param pkg              发起注册的包
     * @param descriptor       规则描述符表
     * */
    private static void registerRule(
            @NonNull StateContext stateContext,
            @NonNull ChunkClassLoader chunkClassLoader,
            @NonNull UsrPackage pkg,
            @NonNull Table descriptor
    ) {
        // id：只写键名，自动加包前缀——跨包唯一性自动成立，脚本无法冒充其他包
        String keyName = optString(descriptor, "id", "规则");
        if (keyName == null) throw new LuaRuntimeException("规则描述符的字段 “id” 必填");
        if (!ELEMENT_KEY_PATTERN.matcher(keyName).matches()) {
            throw new LuaRuntimeException("规则描述符的字段 “id” 只允许小写字母、数字、下划线和连字符：" + keyName);
        }
        NamespacedKey ruleId;
        try {
            ruleId = new NamespacedKey(pkg.id(), keyName);
        } catch (IllegalArgumentException e) {
            throw new LuaRuntimeException("规则描述符的字段 “id” 非法：" + keyName + "（" + e.getMessage() + "）");
        }

        // 标签（可选）：裸键自动加包前缀，跨命名空间写全名；引用必须是已注册标签
        List<@NonNull String> rawTags = optStringList(descriptor, "tags", "规则");
        List<@NonNull NamespacedKey> tags = resolveTags(pkg, rawTags == null ? List.of() : rawTags, "规则");

        // 行为回调（全部可选；未提供时沿用 MatchRule 的默认生命周期行为）
        LuaFunction onEnable = optFunction(descriptor, "on_enable", "规则");
        LuaFunction onDisable = optFunction(descriptor, "on_disable", "规则");
        LuaFunction tick = optFunction(descriptor, "tick", "规则");

        // 登记：add 失败即 id 已被注册，显式抛 Lua 错误使整包加载失败（不静默吞掉重复项）
        DefaultSubtable<RuleEntry<?>, NamespacedKey> subtable =
                Registry.getInstance().category(Categories.RULE);
        OperationResult addResult = subtable.add(LuaRule.entry(
                parseElementMeta(ruleId, descriptor, tags, "规则"),
                pkg,
                () -> new LuaRule.Callbacks(
                        stateContext,
                        chunkClassLoader,
                        onEnable,
                        onDisable,
                        tick,
                        pkg.id() + ":" + keyName
                )
        ));
        if (!addResult.isSuccess()) {
            throw new LuaRuntimeException("规则 id 已被注册：" + ruleId);
        }
        Voidairrace.getInstance().getLogger().info(ServerLanguageService.translate(
                TranslateKeys.Log.REGISTER_API_RULE_REGISTERED, pkg.id(), ruleId));
    }

    /**
     * 处理{@code register("tag", descriptor)}：解析并校验标签描述符，
     * 构造标签注册项登记到注册表
     *
     * @param pkg        发起注册的包
     * @param descriptor 标签描述符表
     * */
    private static void registerTag(@NonNull UsrPackage pkg, @NonNull Table descriptor) {
        // id：只写键名，自动加包前缀（与规则同规则）
        String keyName = optString(descriptor, "id", "标签");
        if (keyName == null) throw new LuaRuntimeException("标签描述符的字段 “id” 必填");
        if (!ELEMENT_KEY_PATTERN.matcher(keyName).matches()) {
            throw new LuaRuntimeException("标签描述符的字段 “id” 只允许小写字母、数字、下划线和连字符：" + keyName);
        }
        NamespacedKey tagId;
        try {
            tagId = new NamespacedKey(pkg.id(), keyName);
        } catch (IllegalArgumentException e) {
            throw new LuaRuntimeException("标签描述符的字段 “id” 非法：" + keyName + "（" + e.getMessage() + "）");
        }

        // 登记：add 失败即 id 已被注册，显式抛 Lua 错误使整包加载失败（不静默吞掉重复项）
        DefaultSubtable<TagEntry, NamespacedKey> subtable = Registry.getInstance().category(Categories.TAG);
        // 标签自身不再携带标签（无嵌套语义）
        OperationResult addResult = subtable.add(new TagEntry(parseElementMeta(tagId, descriptor, List.of(), "标签")));
        if (!addResult.isSuccess()) {
            throw new LuaRuntimeException("标签 id 已被注册：" + tagId);
        }
        Voidairrace.getInstance().getLogger().info(ServerLanguageService.translate(
                TranslateKeys.Log.REGISTER_API_TAG_REGISTERED, pkg.id(), tagId));
    }

    /**
     * 解析元素描述符的标签引用列表为 NamespacedKey，并校验引用合法性<br>
     * 每项按序经过：格式（NamespacedKey 可构造）→ 命名空间（自身 / 插件 / 依赖链上游）
     * → 存在性（TAG 类别已注册）三重校验，任一不过即抛 Lua 错误
     *
     * @param pkg     发起注册的包（提供允许的命名空间与自身前缀）
     * @param rawTags 描述符中的原始标签字符串列表
     * @param kind    元素类别名（「地图」/「规则」），仅用于错误消息
     *
     * @return 解析并去重后的标签 Id 列表（保持声明顺序）
     * */
    private static @NonNull List<@NonNull NamespacedKey> resolveTags(
            @NonNull UsrPackage pkg,
            @NonNull List<@NonNull String> rawTags,
            @NonNull String kind
    ) {
        List<@NonNull NamespacedKey> resolved = new ArrayList<>();
        Set<@NonNull String> seen = new HashSet<>();
        for (String raw : rawTags) {
            NamespacedKey tagId;
            try {
                // 含冒号视作全名引用（其他包 / 插件的标签），否则为裸键自动加自身前缀
                if (raw.contains(":")) {
                    int separator = raw.indexOf(':');
                    tagId = new NamespacedKey(raw.substring(0, separator), raw.substring(separator + 1));
                } else {
                    tagId = new NamespacedKey(pkg.id(), raw);
                }
            } catch (IllegalArgumentException e) {
                throw new LuaRuntimeException(kind + "描述符的标签引用格式非法：“" + raw + "”（" + e.getMessage() + "）");
            }
            // 命名空间白名单：自身、插件、依赖链上游的包
            Set<@NonNull String> allowedNamespaces = new HashSet<>(pkg.dependencies());
            allowedNamespaces.add(pkg.id());
            allowedNamespaces.add(Plugin.ns);
            if (!allowedNamespaces.contains(tagId.getNamespace())) {
                throw new LuaRuntimeException(kind + "描述符的标签引用越出可引用范围：“" + raw
                        + "”（允许的命名空间：自身、" + Plugin.ns + "、依赖的包 " + pkg.dependencies() + "）");
            }
            // 存在性：引用的标签必须已注册——同一脚本内 register("tag") 须先于引用它的元素注册
            if (Registry.getInstance().category(Categories.TAG).get(tagId) == null) {
                throw new LuaRuntimeException(kind + "描述符引用的标签未注册：" + tagId + "（标签必须先于引用它的元素注册）");
            }
            if (seen.add(tagId.asString())) resolved.add(tagId);
        }
        return resolved;
    }

    /**
     * 从元素描述符解析展示元数据（除 Id 与标签外的全部字段，均为可选）<br>
     * 供地图、规则与标签注册共用——三类元素的描述符展示字段完全同构；
     * 展示字段收字符串（= 纯文本组件快捷路径）或文本组件描述符表
     * */
    private static @NonNull GameElementMeta parseElementMeta(
            @NonNull NamespacedKey id,
            @NonNull Table descriptor,
            @NonNull List<@NonNull NamespacedKey> tags,
            @NonNull String kind
    ) {
        // 展示信息（全部可选）；缺省转为空值约定（空列表 / 空组件 / 0），避免 mainName 取首元素时越界
        Long version = optPositiveLong(descriptor, "version", kind);
        return new GameElementMeta(
                id,
                TextComponents.listFromLua(descriptor.rawget(ByteString.of("names")), kind + "描述符的字段 “names”"),
                TextComponents.listFromLua(descriptor.rawget(ByteString.of("description")), kind + "描述符的字段 “description”"),
                TextComponents.listFromLua(descriptor.rawget(ByteString.of("authors")), kind + "描述符的字段 “authors”"),
                TextComponents.fromLua(descriptor.rawget(ByteString.of("display_version")), kind + "描述符的字段 “display_version”"),
                version == null ? 0L : version,
                TextComponents.listFromLua(descriptor.rawget(ByteString.of("links")), kind + "描述符的字段 “links”"),
                tags
        );
    }

    /**
     * 读取描述符中可选的字符串字段：缺省返回{@code null}；存在但不是字符串即抛 Lua 错误
     *
     * @param kind 元素类别名（「规则」/「标签」），仅用于错误消息
     * */
    private static @Nullable String optString(@NonNull Table descriptor, @NonNull String field, @NonNull String kind) {
        Object value = descriptor.rawget(ByteString.of(field));
        if (value == null) return null;
        if (!(value instanceof ByteString bs)) {
            throw new LuaRuntimeException(kind + "描述符的字段 “" + field + "” 必须是字符串");
        }
        return bs.decode();
    }

    /**
     * 读取描述符中可选的正整数字段（Lua 整数）：缺省返回{@code null}；类型不对或小于 1 即抛 Lua 错误
     *
     * @param kind 元素类别名（「规则」/「标签」），仅用于错误消息
     * */
    private static @Nullable Long optPositiveLong(
            @NonNull Table descriptor, @NonNull String field, @NonNull String kind) {
        Object value = descriptor.rawget(ByteString.of(field));
        if (value == null) return null;
        if (!(value instanceof Long version) || version < 1) {
            throw new LuaRuntimeException(kind + "描述符的字段 “" + field + "” 必须是正整数");
        }
        return version;
    }

    /**
     * 读取描述符中可选的字符串数组字段（Lua 数组表，键 1..n 连续）：
     * 缺省返回{@code null}；存在但不是表或含非字符串项即抛 Lua 错误
     *
     * @param kind 元素类别名（「规则」/「标签」），仅用于错误消息
     * */
    private static @Nullable List<@NonNull String> optStringList(
            @NonNull Table descriptor, @NonNull String field, @NonNull String kind) {
        Object value = descriptor.rawget(ByteString.of(field));
        if (value == null) return null;
        if (!(value instanceof Table table)) {
            throw new LuaRuntimeException(kind + "描述符的字段 “" + field + "” 必须是字符串数组");
        }
        List<@NonNull String> list = new ArrayList<>();
        long index = 1;
        while (true) {
            Object item = table.rawget(index);
            if (item == null) break;
            if (!(item instanceof ByteString bs)) {
                throw new LuaRuntimeException(kind + "描述符的字段 “" + field + "” 的第 " + index + " 项必须是字符串");
            }
            list.add(bs.decode());
            index++;
        }
        return list;
    }

    /**
     * 读取描述符中可选的函数字段：缺省返回{@code null}；存在但不是函数即抛 Lua 错误
     *
     * @param kind 元素类别名（「规则」/「标签」），仅用于错误消息
     * */
    private static @Nullable LuaFunction optFunction(
            @NonNull Table descriptor, @NonNull String field, @NonNull String kind) {
        Object value = descriptor.rawget(ByteString.of(field));
        if (value == null) return null;
        if (!(value instanceof LuaFunction fn)) {
            throw new LuaRuntimeException(kind + "描述符的字段 “" + field + "” 必须是函数");
        }
        return fn;
    }

    // ------ 全局表 register 的 Java 函数 ------

    /**
     * {@code register.<类别>(descriptor)}：向注册表注册一个游戏元素<br>
     * 类别在装配期固定为 {@code map}（游戏地图）、{@code rule}（比赛规则）与
     * {@code tag}（游戏元素标签）三实例，运行期参数只有描述符表；
     * 校验失败抛 Lua 错误，使整包加载失败
     * */
    private static final class RegisterFunction extends AbstractFunction1 {
        /** 共享 Lua 状态上下文（注册规则时构造回调用） */
        private final @NonNull StateContext stateContext;

        /** 编译产物类加载器（注册规则时构造回调用） */
        private final @NonNull ChunkClassLoader chunkClassLoader;

        /** 发起注册的包 */
        private final @NonNull UsrPackage pkg;

        /** 本实例负责的注册类别（"map"/"rule"/"tag"，装配期固定） */
        private final @NonNull String category;

        private RegisterFunction(
                @NonNull StateContext stateContext,
                @NonNull ChunkClassLoader chunkClassLoader,
                @NonNull UsrPackage pkg,
                @NonNull String category
        ) {
            this.stateContext = stateContext;
            this.chunkClassLoader = chunkClassLoader;
            this.pkg = pkg;
            this.category = category;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context, @Nullable Object arg1) {
            if (!(arg1 instanceof Table descriptor)) {
                throw new LuaRuntimeException("register." + category + "：参数（元素描述符）必须是表");
            }
            switch (category) {
                case "map" -> registerMap(stateContext, chunkClassLoader, pkg, descriptor);
                case "rule" -> registerRule(stateContext, chunkClassLoader, pkg, descriptor);
                case "tag" -> registerTag(pkg, descriptor);
                default -> throw new IllegalStateException("装配期固定的注册类别非法：" + category);
            }
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState) throws NonsuspendableFunctionException {
            // 本函数不会暂停，没有可恢复的挂起状态
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }
}
