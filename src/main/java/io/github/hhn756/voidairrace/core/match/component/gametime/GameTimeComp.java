package io.github.hhn756.voidairrace.core.match.component.gametime;

import io.github.hhn756.voidairrace.Voidairrace;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.core.match.ComponentPriority;
import io.github.hhn756.voidairrace.core.match.DataKey;
import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.core.match.componentbase.*;
import io.github.hhn756.voidairrace.event.MatchRemainingChangedEvent;
import io.github.hhn756.voidairrace.event.MatchTimeExpiredEvent;
import io.github.hhn756.voidairrace.infrastructure.config.Config;
import io.github.hhn756.voidairrace.infrastructure.config.file.GameSettingKey;
import io.github.hhn756.voidairrace.infrastructure.config.file.PublicFile;
import io.github.hhn756.voidairrace.result.OperationResult;
import io.github.hhn756.voidairrace.result.ValueResult;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Range;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 游戏时间管理组件：维护比赛剩余时间，提供时间变更事件
 */
public class GameTimeComp extends MatchComp
        implements StartableComp<GameTimeComp.GameTimeSA, CustomData>,
        EndableComp<CustomData, CustomData>,
        ConfigurableComp<GameTimeComp.GameTimeECFG, GameTimeComp.GameTimeConfig> {

    // ==================== 组件内部状态 ====================

    private int remaining; // tick
    private Match match;
    /**
     * 剩余游戏时间是否随游戏主循环减少
     * */
    private boolean timeFlow = true;
    private BukkitTask tickTask;

    // ==================== ConfigurableComp 实现 ====================

    public static final DataKey<GameTimeConfig> CONFIG_KEY = DataKey.of(GameTimeComp.class, GameTimeConfig.class);

    @Override
    public DataKey<GameTimeConfig> getConfigKey() {
        return CONFIG_KEY;
    }

    @Override
    public @NonNull ValueResult<GameTimeConfig> createCustomConfig(@Nullable GameTimeECFG expected) {
        int duration = 12000; // 默认10分钟（20ticks/sec * 60sec * 10 = 12000）;
        if (expected != null) {
            // 尝试读取预期配置
            duration = expected.expectedDuration();
        } else {
            // 尝试读取配置文件
            Integer configValue = Config.getInstance()
                    .getYmlConfig(PublicFile.GAME_SETTING)
                    .get(GameSettingKey.MATCH_DURATION,null);
            if (configValue != null) {
                duration = configValue;
            }
        }

        return ValueResult.success(new GameTimeConfig(duration));
    }

    @Override
    public @NonNull ValueResult<GameTimeConfig> createDefaultConfig() {
        return ValueResult.success(new GameTimeConfig(12000));
    }

    public @NonNull OperationResult validateConfig(@NonNull GameTimeConfig config) {
        if (config.duration() <= 0) {
            // 参数值属于服务端参数，只进技术性消息
            return OperationResult.failure(
                    TranslateKeys.BaseComponents.GAME_TIME_COMP_INVALID_DURATION,
                    "比赛时长非法：" + config.duration()
            );
        }
        return OperationResult.success();
    }

    // ==================== StartableComp 实现 ====================

    @Override
    public @NonNull ValueResult<CustomData> install(@NonNull Match match, @Nullable GameTimeSA startArg) {
        this.match = match;
        GameTimeConfig config = match.config().dataOf(GameTimeComp.CONFIG_KEY);
        if (config == null) {
            return ValueResult.failure(TranslateKeys.BaseComponents.GAME_TIME_COMP_NO_CONFIG);
        }

        int initialTime = config.duration();
        if (startArg != null && startArg.initialRemaining() > 0) {
            initialTime = startArg.initialRemaining();
        }
        remaining = initialTime;

        tickTask = Bukkit.getScheduler().runTaskTimer(
                Voidairrace.getInstance(),
                () -> {
                    if (!timeFlow || remaining <= 0) return;
                    int old = remaining;
                    remaining = remaining - 1;
                    // 倒计时的每 tick 变化走自己的小事件（Boss 条这类订阅者只关心时间），
                    // 归零时刻再单独派发一次，订阅者按需选择，不必再从“比赛状态变化”里嗅探
                    new MatchRemainingChangedEvent(match, old, remaining).callEvent();
                    if (remaining == 0) new MatchTimeExpiredEvent(match).callEvent();
                },
                0L,
                1L
        );

        return ValueResult.success(new GameTimeSC(initialTime));
    }

    @Override
    public @Range(from = 0, to = Integer.MAX_VALUE) int getInstallPriority() {
        return ComponentPriority.HIGH.getValue();
    }

    // ==================== 公共 API ====================

    /**
     * @return 剩余游戏时长，单位tick
     * */
    public int getRemaining() {
        return remaining;
    }

    /**
     * 修改剩余游戏时长
     *
     * @param n 指定tick数
     * */
    public void setRemaining(@Range(from = 0, to = Integer.MAX_VALUE) int n) {
        int old = this.remaining;
        this.remaining = n;
        if (old == n || match == null) return;
        new MatchRemainingChangedEvent(match, old, n).callEvent();
        if (n == 0) new MatchTimeExpiredEvent(match).callEvent();
    }

    /**
     * @return 剩余游戏时间是否随tick减少
     * */
    public boolean isTimeFlow() {
        return timeFlow;
    }

    /**
     * 设置剩余游戏时间是否随tick减少
     *
     * @param newValue {@code true}减少；{@code false}不减少v
     * */
    public void setTimeFlow(boolean newValue) {
        this.timeFlow = newValue;
    }

    @Override
    public @NonNull ValueResult<CustomData> uninstall(
            @NonNull Match match,
            @Nullable CustomData endArg) {
        tickTask.cancel();

        return ValueResult.empty();
    }

    /**
     * @param duration 初始比赛时间（可由其他部分动态修改），比赛将在指定 tick 后结束
     */
    public record GameTimeConfig(int duration) implements CustomData {
    
        @Override
        public @NonNull Class<? extends MatchComp> source() {
            return GameTimeComp.class;
        }
    }

    /**
     * @param expectedDuration tick
     */
    public record GameTimeECFG(int expectedDuration) implements CustomData {
    
        @Override
        public @NonNull Class<? extends MatchComp> source() {
            return GameTimeComp.class;
        }
    }

    /**
     * @param initialRemaining 可以包含初始剩余时间的覆盖值
     */
    public record GameTimeSA(int initialRemaining) implements CustomData {
    
        @Override
        public @NonNull Class<? extends MatchComp> source() {
            return GameTimeComp.class;
        }
    }

    public record GameTimeSC(int startTime) implements CustomData {
    
        @Override
        public @NonNull Class<? extends MatchComp> source() {
            return GameTimeComp.class;
        }
    }
}
