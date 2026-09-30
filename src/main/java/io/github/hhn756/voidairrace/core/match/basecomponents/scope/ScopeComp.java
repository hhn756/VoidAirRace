package io.github.hhn756.voidairrace.core.match.basecomponents.scope;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.core.match.ComponentPriority;
import io.github.hhn756.voidairrace.core.match.DataKey;
import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.core.match.componentbase.CustomData;
import io.github.hhn756.voidairrace.core.match.componentbase.EndableComp;
import io.github.hhn756.voidairrace.core.match.componentbase.MatchComp;
import io.github.hhn756.voidairrace.result.ValueResult;
import org.jetbrains.annotations.Range;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.logging.Logger;

/**
 * 管理比赛范围
 */
public class ScopeComp extends MatchComp
        implements EndableComp<CustomData, CustomData> {
    private final Set<MatchArea> areas = new HashSet<>();

    @Override
    public @NonNull DataKey<?> getECK() {
        return DataKey.of(ScopeComp.class, CustomData.class);
    }

    @Override
    public @NonNull ValueResult<CustomData> uninstall(@NonNull Match match, @Nullable CustomData endArg) {
        Logger logger = VoidAirRace.getInstance().getLogger();
        // 归还所有竞技场
        for (MatchArea area : areas) {
            try {
                area.token().returnArena();
            } catch (Exception e) {
                // 记录日志但不影响其他归还
                logger.warning("无法归还竞技场 " + area.token().getArenaId() + ": " + e.getMessage());
            }
        }
        areas.clear();
        return ValueResult.empty();
    }

    public @Range(from = 0, to = Integer.MAX_VALUE) int getUninstallPriority() {
        return ComponentPriority.EXTREMELY_LOW.getValue();
    }

    // ==================== 公共 API ====================

    /**
     * 使比赛 涉及 到指定区域<br>
     * 比赛结束时组件 会 自动归还比赛区域对象中的竞技场借据
     *
     * @param area 指定区域
     * */
    public void widen(@NonNull MatchArea area) {
        areas.add(area);
    }

    /**
     * 使比赛 不再涉及 到指定区域<br>
     * 比赛结束后组件将 不再会 自动归还指定区域中的竞技场借据
     *
     * @param area 指定区域
     * */
    public void reduce(@NonNull MatchArea area) {
        areas.remove(area);
    }

    /**
     * @return 比赛涉及到的所有区域
     * */
    public @NonNull Set<MatchArea> getAllAreas() {
        return Collections.unmodifiableSet(areas);
    }

    /**
     * @param tags 指定的所有标签，用于筛选比赛区域
     *
     * @return 所有有指定的所有标签的比赛区域
     * */
    public @NonNull List<MatchArea> getAreasByTags(@NonNull AreaTag... tags) {
        List<MatchArea> result = new ArrayList<>();
        for (MatchArea area : areas) {
            boolean hasAll = true;
            for (AreaTag tag : tags) {
                if (!area.tags().contains(tag)) {
                    hasAll = false;
                    break;
                }
            }
            if (hasAll) {
                result.add(area);
            }
        }
        return result;
    }
}
