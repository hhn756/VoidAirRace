package io.github.hhn756.voidairrace.addons.script;

import io.github.hhn756.voidairrace.addons.script.api.register.LuaRule;
import io.github.hhn756.voidairrace.addons.usrpackage.UsrPackage;
import io.github.hhn756.voidairrace.core.map.MapComp;
import io.github.hhn756.voidairrace.core.map.MapComp.MapConfig;
import io.github.hhn756.voidairrace.core.map.PlayableGameMap;
import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.core.match.MatchCoordinator;
import io.github.hhn756.voidairrace.core.match.MatchState;
import io.github.hhn756.voidairrace.core.match.component.contestant.ContestantComp;
import io.github.hhn756.voidairrace.core.match.componentbase.MatchComp;
import io.github.hhn756.voidairrace.core.matchrule.MatchRule;
import io.github.hhn756.voidairrace.core.matchrule.RuleComp;
import io.github.hhn756.voidairrace.service.arena.ArenaManager;
import io.github.hhn756.voidairrace.service.arena.ArenaToken;
import net.sandius.rembulan.LuaRuntimeException;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 用户包资源修改的统一入口：按资源归属关系裁定修改权，供各 Lua API 共用<br>
 * 当前规则：
 * <ul>
 *     <li>竞技场资源（场内方块、容器、实体）：只有借入该竞技场的包可以修改</li>
 *     <li>玩家：注册本场地图的包可以修改本场参赛者（含已淘汰者）；本场已启用的
 *         用户 Lua 规则，其来源包获得同等修改权（启用即授予）。其余包只能读取</li>
 * </ul>
 * 决策集中在本类，API 实现不得绕过；后续新增的方块、实体、背包类修改接口
 * 一律先经本类校验。本类不暴露给 Lua：脚本看不到归属对象，只能在调用 API 时触发校验
 * */
public final class MutationAccess {
    /**
     * 用户地图实例 → 发起注册的包：比赛内资源修改权的归属账本<br>
     * 弱键：每场比赛实例化一张地图，比赛结束后地图实例失去引用，条目随 GC 回收
     * */
    private static final Map<@NonNull PlayableGameMap, @NonNull UsrPackage> MAP_OWNERS = new WeakHashMap<>();

    private MutationAccess() {}

    /**
     * 登记用户地图实例的归属包<br>
     * 由用户地图注册项在实例化时调用；Java 内置地图不登记，视为无包归属
     *
     * @param map 地图实例
     * @param pkg 注册该地图的包
     * */
    public static void registerMap(@NonNull PlayableGameMap map, @NonNull UsrPackage pkg) {
        MAP_OWNERS.put(map, pkg);
    }

    /**
     * 校验竞技场资源的修改权（方块、容器、场内实体）<br>
     * 借据无效（已被归还或伪造）或借入者不是调用方即抛 Lua 错误
     *
     * @param pkg   发起修改的包
     * @param token 目标竞技场的借据
     * */
    public static void requireArena(@NonNull UsrPackage pkg, @NonNull ArenaToken token) {
        String borrower = ArenaManager.getInstance().borrowerOf(token);
        if (borrower == null) {
            throw new LuaRuntimeException("竞技场借据无效，不能修改");
        }
        if (!borrower.equals(pkg.id())) {
            throw new LuaRuntimeException("竞技场不属于当前包，不能修改（借入者：" + borrower + "）");
        }
    }

    /**
     * 校验玩家的修改权<br>
     * 目标必须是调用方所发起比赛的参赛者（存活或已淘汰）；
     * 没有进行中的比赛、比赛不是调用方发起、目标不在参赛者之列，均抛 Lua 错误
     *
     * @param pkg    发起修改的包
     * @param player 目标玩家
     * */
    public static void requirePlayer(@NonNull UsrPackage pkg, @NonNull Player player) {
        Match match = requireOwnedActiveMatch(pkg);
        // 配置可以不装参赛者组件：getComp 对缺失组件返回 null 但类型上不体现，先自行兜住
        MatchComp comp = match.config().getAllComponents().get(ContestantComp.class);
        if (!(comp instanceof ContestantComp contestants)) {
            throw new LuaRuntimeException("本场比赛没有参赛者组件，不能修改玩家");
        }
        if (!contestants.contains(player) && !contestants.eliminatedPlayers().contains(player)) {
            throw new LuaRuntimeException("目标玩家不是本场比赛的参赛者，不能修改：" + player.getUniqueId());
        }
    }

    /**
     * 取回调用方发起且尚未结束的比赛<br>
     * 放行{@link MatchState#STARTING}：地图 {@code on_start} 就在此时执行，
     * 参赛者组件（优先级更高）已安装完毕，传送参赛者进场属于合法修改
     *
     * @param pkg 发起修改的包
     *
     * @return 当前比赛
     * */
    private static @NonNull Match requireOwnedActiveMatch(@NonNull UsrPackage pkg) {
        Match match = MatchCoordinator.getInstance().getCurrentMatch();
        if (match == null || (match.state() != MatchState.STARTING && match.state() != MatchState.IN_PROGRESS)) {
            throw new LuaRuntimeException("当前没有进行中的比赛，不能修改比赛内资源");
        }
        MapComp.MapConfig config = match.config().dataOf(MapComp.CONFIG_KEY);
        if (config == null) {
            throw new LuaRuntimeException("比赛地图配置缺失，不能修改比赛内资源");
        }
        if (!ownsMatch(match, config, pkg)) {
            throw new LuaRuntimeException("比赛不属于当前包，不能修改比赛内资源");
        }
        return match;
    }

    /**
     * 裁定调用包是否拥有本场比赛内资源的修改权<br>
     * 两条来源：注册本场地图的包；本场已启用用户 Lua 规则的来源包——地图或其他包
     * 主动启用某规则，即视为把本场玩家交给该规则处置
     *
     * @param match  目标比赛
     * @param config 该比赛的地图配置
     * @param pkg    发起修改的包
     *
     * @return 有修改权时为{@code true}
     * */
    private static boolean ownsMatch(
            @NonNull Match match,
            @NonNull MapConfig config,
            @NonNull UsrPackage pkg
    ) {
        UsrPackage mapOwner = MAP_OWNERS.get(config.map());
        if (mapOwner != null && mapOwner.id().equals(pkg.id())) return true;

        MatchComp comp = match.config().getAllComponents().get(RuleComp.class);
        if (!(comp instanceof RuleComp rules)) return false;
        for (MatchRule rule : rules.getActiveRules()) {
            if (rule instanceof LuaRule luaRule && luaRule.source().id().equals(pkg.id())) return true;
        }
        return false;
    }
}
