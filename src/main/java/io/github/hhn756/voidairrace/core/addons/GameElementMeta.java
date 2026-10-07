package io.github.hhn756.voidairrace.core.addons;

import io.github.hhn756.voidairrace.constants.TranslateKeys;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.Range;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Objects;

/**
 * 记录一个游戏元素的元数据。全部字段非 null，未提供的信息使用约定的空值表示：
 * 集合字段为空列表、{@code displayVersion} 为 {@link Component#empty()}、{@code version} 为 0
 *
 * @param id 游戏元素的唯一标识，不可重复，参数内部计算
 * @param names 游戏元素名称，第一个元素为主要（常用）名称，后续所有均为别名。不参与内部计算；空列表表示未提供
 * @param description 显示给玩家看的描述，每个元素对应一行；空列表表示未提供
 * @param authors 作者列表；空列表表示未提供
 * @param displayVersion 显示给玩家看的版本号，例如“1.23.4”、“25-07-9”、“第二版改”。不参与内部计算；未提供时为 {@link Component#empty()}
 * @param version 代表用户内容版本的新旧度，用于内部计算，值越大越新；未声明时为 0（视为最旧）
 * @param links 用户内容相关的链接（常为网页）；空列表表示未提供
 * @param tags 元素携带的标签（可为空列表表示无标签），
 *             标签本身是注册元素（TAG 类别），用于按性质筛选元素
 * */
public record GameElementMeta(
        @NonNull NamespacedKey id,
        @NonNull List<@NonNull Component> names,
        @NonNull List<@NonNull Component> description,
        @NonNull List<@NonNull Component> authors,
        @NonNull Component displayVersion,
        @NonNull @Range(from = 0, to = Long.MAX_VALUE) Long version,
        @NonNull List<@NonNull Component> links,
        @NonNull List<@NonNull NamespacedKey> tags
) {
    /**
     * 紧凑构造器：校验全部字段非 null，并复制集合字段使其不可变化
     * */
    public GameElementMeta {
        id = Objects.requireNonNull(id, "id 不可为 null");
        names = List.copyOf(Objects.requireNonNull(names, "names 不可为 null，未提供请传空列表"));
        description = List.copyOf(Objects.requireNonNull(description, "description 不可为 null，未提供请传空列表"));
        authors = List.copyOf(Objects.requireNonNull(authors, "authors 不可为 null，未提供请传空列表"));
        displayVersion = Objects.requireNonNull(displayVersion, "displayVersion 不可为 null，未提供请传 Component.empty()");
        version = Objects.requireNonNull(version, "version 不可为 null，未声明请传 0");
        links = List.copyOf(Objects.requireNonNull(links, "links 不可为 null，未提供请传空列表"));
        tags = List.copyOf(Objects.requireNonNull(tags, "tags 不可为 null，无标签请传空列表"));
    }

    /**
     * @return 元素的第一个显示名称（主要名称），未提供时返回包含默认元素名的文本组件
     * */
    public @NonNull Component mainName() {
        if (names.isEmpty()) return Component.translatable(
                TranslateKeys.Addons.GAME_ELEMENT_META_DEFAULT_ELEMENT_NAME
        );
        return names.getFirst();
    }

    /**
     * 检查两个游戏元素的{@link GameElementMeta#id}是否相等
     * */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GameElementMeta GameElementMeta = (GameElementMeta) o;
        return Objects.equals(id, GameElementMeta.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    // --------------------------------

    /**
     * @param id 指定id
     *
     * @return 只包含指定id，其他信息为空值约定（空列表 / {@link Component#empty()} / 0）的游戏元素元数据
     * */
    public static @NonNull GameElementMeta onlyId(@NonNull NamespacedKey id) {
        return new GameElementMeta(id, List.of(), List.of(), List.of(), Component.empty(), 0L, List.of(), List.of());
    }
}
