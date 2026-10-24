package io.github.hhn756.voidairrace.core.match.component.scope;

import io.github.hhn756.voidairrace.service.arena.ArenaToken;
import org.jspecify.annotations.NonNull;

import java.util.HashSet;
import java.util.List;

/**
 * 代表比赛涉及到的一个区域（即“比赛涉及范围”）
 * */
public class MatchArea {
    /** 此区域（竞技场）的借据 */
    public @NonNull ArenaToken token;
    /** 此区域的标签，用于标识和操作具有共同/相似属性的某类区域 */
    public @NonNull HashSet<AreaTag> tags;

    public MatchArea(@NonNull ArenaToken token, AreaTag... tags) {
        this.token = token;
        this.tags = new HashSet<>(List.of(tags));
    }

    public MatchArea(@NonNull ArenaToken token, @NonNull HashSet<AreaTag> tags) {
        this.token = token;
        this.tags = new HashSet<>(tags);
    }

    public ArenaToken token() {
        return token;
    }

    public HashSet<AreaTag> tags() {
        return tags;
    }
}
