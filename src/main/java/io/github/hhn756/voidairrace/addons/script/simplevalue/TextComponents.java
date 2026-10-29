package io.github.hhn756.voidairrace.addons.script.simplevalue;

import io.github.hhn756.voidairrace.infrastructure.util.lua.ApiArgs;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.LuaRuntimeException;
import net.sandius.rembulan.Table;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lua 文本组件描述符 → Adventure {@link Component} 的单向转换器<br>
 * 文本组件在 Lua 侧是数据形状而非函数：脚本构造描述符表传给接收点
 * （{@code server.broadcast}、{@code register.*} 的展示字段），由本类在接收点完成转换；
 * 不设全局表（违背「全局表承载函数」纪律），不做反向转换（Component 无法回传 Lua）
 * <p>
 * 描述符协议（snake_case，样式字段内联在组件表上，同 Minecraft Wiki 文本组件定义）：
 * <ul>
 *   <li>{@code type}：{@code "text"}（缺省，字段 {@code text}）/ {@code "translatable"}
 *       （{@code translate} 必填，{@code fallback}，{@code args} 数组可嵌套任意描述符）/
 *       {@code "keybind"} / {@code "score"}（{@code objective} 必填）/ {@code "selector"} /
 *       {@code "nbt"}（{@code block}/{@code entity}/{@code storage} 三选一）</li>
 *   <li>样式：{@code color}（颜色名或 {@code #RRGGBB}）、三态装饰
 *       {@code bold}/{@code italic}/{@code underlined}/{@code strikethrough}/{@code obfuscated}
 *       （true/false/nil，nil 继承）、{@code font}、{@code insertion}</li>
 *   <li>{@code click_event}：{@code action}（闭集）+ {@code value}；
 *       {@code hover_event}：{@code show_text}/{@code show_item}/{@code show_entity}</li>
 *   <li>{@code extra}：子组件描述符数组，任意嵌套（深度上限 {@value #MAX_DEPTH}，
 *       Lua 表可自引用，无上限将栈溢出）</li>
 * </ul>
 * 简字符串快捷路径：接收点参数直接给字符串 = 纯文本组件。
 * 校验失败抛 Lua 错误，错误消息携带嵌套路径（{@code what} 参数）
 * */
public final class TextComponents {
    /** 描述符嵌套深度上限：防 Lua 表自引用导致 Java 栈溢出 */
    private static final int MAX_DEPTH = 32;

    /** 静态工具类，不可实例化 */
    private TextComponents() {}

    /**
     * 把 Lua 值转换为文本组件：字符串 = 纯文本快捷路径，表 = 描述符
     *
     * @param arg  Lua 原值（nil 视为空组件）
     * @param what 参数用途说明，用于错误消息（自动携带嵌套路径）
     *
     * @return 转换后的组件；arg 为 null 时为空组件
     * */
    public static @NonNull Component fromLua(@Nullable Object arg, @NonNull String what) {
        if (arg == null) return Component.empty();
        if (arg instanceof ByteString bs) return Component.text(bs.decode());
        if (arg instanceof Table table) return build(table, what, 0);
        throw new LuaRuntimeException(what + "必须是字符串或文本组件描述符表");
    }

    /**
     * 把 Lua 数组表（键 1..n 连续）逐项转换为组件列表
     *
     * @param arg  Lua 原值（null 视为空列表）
     * @param what 参数用途说明，用于错误消息
     *
     * @return 组件列表；arg 为 null 时为空列表
     * */
    public static @NonNull List<@NonNull Component> listFromLua(@Nullable Object arg, @NonNull String what) {
        if (arg == null) return List.of();
        if (!(arg instanceof Table table)) {
            throw new LuaRuntimeException(what + "必须是数组（文本组件描述符或字符串的列表）");
        }
        List<@NonNull Component> list = new ArrayList<>();
        long index = 1;
        while (true) {
            Object item = table.rawget(index);
            if (item == null) break;
            list.add(fromLua(item, what + "的第 " + index + " 项"));
            index++;
        }
        return List.copyOf(list);
    }

    /**
     * 按描述符构造组件：解析 type 分派到对应类型构造，再应用样式与事件
     *
     * @param table 组件描述符表
     * @param what  错误消息路径前缀（调用点参数说明，嵌套时追加字段名）
     * @param depth 当前嵌套深度
     * */
    private static @NonNull Component build(@NonNull Table table, @NonNull String what, int depth) {
        if (depth >= MAX_DEPTH) {
            throw new LuaRuntimeException(what + "：文本组件描述符嵌套过深（上限 " + MAX_DEPTH + "）");
        }
        String type = ApiArgs.optStringField(table, "type", what);
        Component component = switch (type == null ? "text" : type) {
            case "text" -> buildText(table, what);
            case "translatable" -> buildTranslatable(table, what, depth);
            case "keybind" -> buildKeybind(table, what);
            case "score" -> buildScore(table, what);
            case "selector" -> buildSelector(table, what);
            case "nbt" -> buildNbt(table, what);
            default -> throw new LuaRuntimeException(what + "的字段 “type” 非法：\"" + type
                    + "\"（可用：text / translatable / keybind / score / selector / nbt）");
        };
        component = applyStyle(component, table, what);
        component = applyClickEvent(component, table, what);
        component = applyHoverEvent(component, table, what);
        return applyExtra(component, table, what, depth);
    }

    // ------ 类型构造 ------

    /** text：字段 {@code text}（可选，缺省空文本）；存在但非字符串即抛错 */
    private static @NonNull Component buildText(@NonNull Table table, @NonNull String what) {
        String text = ApiArgs.optStringField(table, "text", what);
        return text == null ? Component.empty() : Component.text(text);
    }

    /**
     * translatable：{@code translate} 必填；{@code fallback} 可选；
     * {@code args} 为数组，每项可嵌套任意描述符（Adventure 原生支持组件参数）
     * */
    private static @NonNull Component buildTranslatable(@NonNull Table table, @NonNull String what, int depth) {
        String translate = ApiArgs.expectStringField(table, "translate", what);
        String fallback = ApiArgs.optStringField(table, "fallback", what);
        Object rawArgs = table.rawget(ByteString.of("args"));
        List<Component> args = rawArgs == null
                ? List.of()
                : listFromLua(rawArgs, what + "的字段 “args”");
        var component = fallback == null
                ? Component.translatable(translate)
                : Component.translatable(translate, fallback);
        return args.isEmpty() ? component : component.arguments(args);
    }

    /** keybind：{@code keybind} 必填（按键绑定标识，如 {@code key.jump}） */
    private static @NonNull Component buildKeybind(@NonNull Table table, @NonNull String what) {
        return Component.keybind(ApiArgs.expectStringField(table, "keybind", what));
    }

    /** score：{@code objective} 必填；{@code name} 缺省空串（= 读者自身的记分板项） */
    private static @NonNull Component buildScore(@NonNull Table table, @NonNull String what) {
        String objective = ApiArgs.expectStringField(table, "objective", what);
        String name = ApiArgs.optStringField(table, "name", what);
        return Component.score(name == null ? "" : name, objective);
    }

    /** selector：{@code selector} 必填（实体选择器，如 {@code @p}）；{@code separator} 可选 */
    private static @NonNull Component buildSelector(@NonNull Table table, @NonNull String what) {
        String selector = ApiArgs.expectStringField(table, "selector", what);
        String separator = ApiArgs.optStringField(table, "separator", what);
        return separator == null ? Component.selector(selector) : Component.selector(selector, Component.text(separator));
    }

    /**
     * nbt：{@code block}（坐标串，支持 ~ 相对坐标）/ {@code entity}（选择器或 UUID 串）/
     * {@code storage}（资源路径）三选一，多选或全缺即抛错；{@code nbt} 路径必填；
     * {@code interpret} / {@code separator} 可选
     * */
    private static @NonNull Component buildNbt(@NonNull Table table, @NonNull String what) {
        String block = ApiArgs.optStringField(table, "block", what);
        String entity = ApiArgs.optStringField(table, "entity", what);
        String storage = ApiArgs.optStringField(table, "storage", what);
        String nbtPath = ApiArgs.expectStringField(table, "nbt", what);
        boolean interpret = table.rawget(ByteString.of("interpret")) != null;
        String separator = ApiArgs.optStringField(table, "separator", what);
        Component separatorComponent = separator == null ? null : Component.text(separator);

        int sources = (block != null ? 1 : 0) + (entity != null ? 1 : 0) + (storage != null ? 1 : 0);
        if (sources != 1) {
            throw new LuaRuntimeException(what + "：nbt 组件的来源字段 “block” / “entity” / “storage” 必须三选一");
        }
        if (block != null) {
            BlockNBTComponent.Pos pos;
            try {
                pos = BlockNBTComponent.Pos.fromString(block);
            } catch (IllegalArgumentException e) {
                throw new LuaRuntimeException(what + "的字段 “block” 非法坐标：" + block + "（" + e.getMessage() + "）");
            }
            NBTComponent component = (NBTComponent) Component.blockNBT(nbtPath, pos).interpret(interpret);
            return separatorComponent == null ? (Component) component : (Component) component.separator(separatorComponent);
        }
        if (entity != null) {
            EntityNBTComponent component = Component.entityNBT(entity, nbtPath).interpret(interpret);
            return separatorComponent == null ? component : component.separator(separatorComponent);
        }
        Key storageKey = NamespaceKey.expect(storage, what + "的字段 “storage”");
        StorageNBTComponent component = Component.storageNBT(nbtPath, storageKey).interpret(interpret);
        return separatorComponent == null ? component : component.separator(separatorComponent);
    }

    // ------ 样式与事件 ------

    /**
     * 应用描述符中的样式字段：color（颜色名或 #RRGGBB）、五项三态装饰
     * （true/false/nil，nil 继承不写入）、font、insertion
     * */
    private static @NonNull Component applyStyle(
            @NonNull Component component, @NonNull Table table, @NonNull String what) {
        String colorName = ApiArgs.optStringField(table, "color", what);
        if (colorName != null) {
            TextColor color = parseColor(colorName, what);
            component = component.color(color);
        }
        component = applyDecoration(component, table, "bold", TextDecoration.BOLD, what);
        component = applyDecoration(component, table, "italic", TextDecoration.ITALIC, what);
        component = applyDecoration(component, table, "underlined", TextDecoration.UNDERLINED, what);
        component = applyDecoration(component, table, "strikethrough", TextDecoration.STRIKETHROUGH, what);
        component = applyDecoration(component, table, "obfuscated", TextDecoration.OBFUSCATED, what);
        String font = ApiArgs.optStringField(table, "font", what);
        if (font != null) component = component.font(NamespaceKey.expect(font, what + "的字段 “font”"));
        String insertion = ApiArgs.optStringField(table, "insertion", what);
        if (insertion != null) component = component.insertion(insertion);
        return component;
    }

    /** 三态装饰：字段缺省（nil）= 继承不写入；true/false 显式置位/取消；非布尔即抛错 */
    private static @NonNull Component applyDecoration(
            @NonNull Component component, @NonNull Table table,
            @NonNull String field, @NonNull TextDecoration decoration, @NonNull String what) {
        Object value = table.rawget(ByteString.of(field));
        if (value == null) return component;
        if (!(value instanceof Boolean b)) {
            throw new LuaRuntimeException(what + "的字段 “" + field + "” 必须是布尔值或省略");
        }
        return component.decoration(decoration, TextDecoration.State.byBoolean(b));
    }

    /** 颜色解析：先按 NamedTextColor 名称（如 red）解析，再按 #RRGGBB 十六进制解析 */
    private static @NonNull TextColor parseColor(@NonNull String colorName, @NonNull String what) {
        NamedTextColor named = NamedTextColor.NAMES.value(colorName.toLowerCase(Locale.ROOT));
        if (named != null) return named;
        // fromHexString 对非法输入返回 null（部分版本抛异常），两种情况都按非法颜色处理——
        // 不能把 null 交给 component.color()，那等于“没设颜色”，非法输入会被静默接受
        TextColor hex;
        try {
            hex = TextColor.fromHexString(colorName);
        } catch (IllegalArgumentException ignored) {
            hex = null;
        }
        if (hex != null) return hex;
        throw new LuaRuntimeException(what + "的字段 “color” 非法：\"" + colorName
                + "\"（可用颜色名或 #RRGGBB）");
    }

    /**
     * click_event：{@code action} 闭集校验 + {@code value} 类型随动作校验<br>
     * 不开放 {@code open_file}：Minecraft 客户端协议不允许该点击事件，组件能构造但在发包时
     * 会被服务端序列化器拒绝，导致消息发不出去、客户端断线
     * */
    private static @NonNull Component applyClickEvent(
            @NonNull Component component, @NonNull Table table, @NonNull String what) {
        Object raw = table.rawget(ByteString.of("click_event"));
        if (raw == null) return component;
        if (!(raw instanceof Table event)) {
            throw new LuaRuntimeException(what + "的字段 “click_event” 必须是表");
        }
        String action = ApiArgs.expectStringField(event, "action", what + "的字段 “click_event”");
        String path = what + "的字段 “click_event”";
        ClickEvent click = switch (action) {
            case "open_url" -> ClickEvent.openUrl(
                    requireUrl(event, path));
            case "run_command" -> ClickEvent.runCommand(
                    ApiArgs.expectString(event.rawget(ByteString.of("value")), path + "的字段 “value”"));
            case "suggest_command" -> ClickEvent.suggestCommand(
                    ApiArgs.expectString(event.rawget(ByteString.of("value")), path + "的字段 “value”"));
            case "change_page" -> ClickEvent.changePage((int) optPositiveLong(
                    event, "value", path));
            case "copy_to_clipboard" -> ClickEvent.copyToClipboard(
                    ApiArgs.expectString(event.rawget(ByteString.of("value")), path + "的字段 “value”"));
            default -> throw new LuaRuntimeException(path + "的字段 “action” 非法：\"" + action
                    + "\"（可用：open_url / run_command / suggest_command / change_page / copy_to_clipboard）");
        };
        return component.clickEvent(click);
    }

    /** open_url 的 value 校验：仅接受 http(s) 链接 */
    private static @NonNull String requireUrl(@NonNull Table event, @NonNull String path) {
        String value = ApiArgs.expectString(event.rawget(ByteString.of("value")), path + "的字段 “value”");
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new LuaRuntimeException(path + "的字段 “value” 必须以 http:// 或 https:// 开头：" + value);
        }
        return value;
    }

    /**
     * hover_event：{@code action} 三选一——show_text（value=字符串/描述符）、
     * show_item（id 必填 + count 可选）、show_entity（type 与 id 必填 + name 可选）
     * */
    private static @NonNull Component applyHoverEvent(
            @NonNull Component component, @NonNull Table table, @NonNull String what) {
        Object raw = table.rawget(ByteString.of("hover_event"));
        if (raw == null) return component;
        if (!(raw instanceof Table event)) {
            throw new LuaRuntimeException(what + "的字段 “hover_event” 必须是表");
        }
        String path = what + "的字段 “hover_event”";
        String action = ApiArgs.expectStringField(event, "action", path);
        HoverEvent<?> hover = switch (action) {
            case "show_text" -> HoverEvent.showText(
                    fromLua(event.rawget(ByteString.of("value")), path + "的字段 “value”"));
            case "show_item" -> {
                Key id = NamespaceKey.expect(ApiArgs.expectStringField(event, "id", path), path + "的字段 “id”");
                Object rawCount = event.rawget(ByteString.of("count"));
                if (rawCount == null) {
                    yield HoverEvent.showItem(id, 1);
                }
                long count = optPositiveLong(event, "count", path);
                yield HoverEvent.showItem(id, (int) count);
            }
            case "show_entity" -> {
                Key type = NamespaceKey.expect(ApiArgs.expectStringField(event, "type", path), path + "的字段 “type”");
                String id = ApiArgs.expectStringField(event, "id", path);
                Object rawName = event.rawget(ByteString.of("name"));
                Component name = rawName == null
                        ? null
                        : fromLua(rawName, path + "的字段 “name”");
                yield HoverEvent.showEntity(HoverEvent.ShowEntity.showEntity(
                        type, java.util.UUID.fromString(id), name));
            }
            default -> throw new LuaRuntimeException(path + "的字段 “action” 非法：\"" + action
                    + "\"（可用：show_text / show_item / show_entity）");
        };
        return component.hoverEvent(hover);
    }

    /** extra：子组件描述符数组，任意嵌套 */
    private static @NonNull Component applyExtra(
            @NonNull Component component, @NonNull Table table, @NonNull String what, int depth) {
        Object raw = table.rawget(ByteString.of("extra"));
        if (raw == null) return component;
        List<@NonNull Component> children = listFromLua(raw, what + "的字段 “extra”");
        return children.isEmpty() ? component : component.append(children);
    }

    // ------ 字段读取 ------

    /** 读取必填正整数字段：缺省、非整数或小于 1 即抛错（click_event 的 change_page 用） */
    private static long optPositiveLong(@NonNull Table table, @NonNull String field, @NonNull String what) {
        Object value = table.rawget(ByteString.of(field));
        if (!(value instanceof Long l) || l < 1) {
            throw new LuaRuntimeException(what + "的字段 \"" + field + "\" 必须是正整数");
        }
        return l;
    }
}
