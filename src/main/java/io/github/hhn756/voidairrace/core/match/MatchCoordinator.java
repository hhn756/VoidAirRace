package io.github.hhn756.voidairrace.core.match;

import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.infrastructure.config.Config;
import io.github.hhn756.voidairrace.infrastructure.config.YamlConfig;
import io.github.hhn756.voidairrace.infrastructure.config.files.FlagsKeys;
import io.github.hhn756.voidairrace.infrastructure.config.files.PublicFiles;
import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import io.github.hhn756.voidairrace.result.OperationResult;
import io.github.hhn756.voidairrace.result.ValueResult;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * 比赛协调器，负责管理比赛的状态<br>
 * 单例模式，通过 {@link #getInstance()} 获取实例
 */
public class MatchCoordinator implements Module {
    private static MatchCoordinator instance;

    @Override
    public Collection<Class<? extends Module>> getRequiredModules() {
        return List.of(Config.class, ComponentRegistrar.class);
    }

    /**
     * 插件启用时执行<br>
     * 恢复异常比赛检查依赖 Config 就绪与组件注册完成
     * */
    private void onLoad() {
        instance = this;
        checkMatchAbort();
    }

    /** 插件禁用时执行 */
    private void onUnload() {
        instance = null;
    }

    /**
     * 恢复在插件启用前异常结束的比赛
     * */
    private void checkMatchAbort() {
        YamlConfig flagConfig = Config.getInstance().getYmlConfig(PublicFiles.FLAGS);
        // 默认值为false：插件初次运行标记文件未创建时视作不存在 异常结束的比赛
        boolean isAborted = flagConfig.get(FlagsKeys.MATCH_ABORTED, false);
        if (isAborted) {
            // TODO: 恢复上次异常结束的比赛


            // 标记 “比赛异常结束已处理”
            flagConfig.set(FlagsKeys.MATCH_ABORTED, false);
        }
    }

    /**
     * 获取协调器实例
     *
     * @return 协调器实例
     * @throws IllegalStateException 如果实例尚未初始化（未调用带参的 getInstance）
     */
    public static MatchCoordinator getInstance() throws NullPointerException {
        if (instance == null) throw new NullPointerException("比赛协调器实例不存在");
        return instance;
    }

    // -----------

    /** 当前正在进行的比赛，若无比赛则为 null */
    private @Nullable Match currentMatch = null;

    private MatchCoordinator() {}

    /**
     * 获取当前比赛状态
     *
     * @return 比赛状态
     */
    public @NonNull MatchState getMatchState() {
        if (currentMatch == null) return MatchState.SCHEDULED;
        return this.currentMatch.state();
    }

    /**
     * 开始一局新比赛
     *
     * @param matchConfig 比赛所使用的配置，为{@code null}时使用默认配置
     *
     * @return 开始结果。失败时沿调用链携带最内层失败原因：
     *         配置创建或比赛实例创建失败时挂到“开始比赛失败”键下，
     *         比赛自身开始失败时挂到“协调器开始比赛失败”键下
     */
    public @NonNull OperationResult startMatch(@Nullable MatchConfig matchConfig) {
        if (matchIsRunning()) return OperationResult.failure(
                TranslateKeys.Match.MATCH_COORDINATOR_START_MATCH_REPEAT_START);

        // 设置标志，指示服务器启动时需要强制结束比赛（防止意外关闭导致状态不一致）
        setAndSaveStopFlag(true);

        // 默认参数值
        if (matchConfig == null) {
            ValueResult<MatchConfig> defaultConfigResult = MatchConfig.createDefault();
            if (!(defaultConfigResult instanceof ValueResult.WithValue(var config))) {
                // 创建默认配置失败/未产出配置：把原因挂到“开始比赛失败”键对上
                return defaultConfigResult.expectValue(
                        TranslateKeys.Match.START_START_FAILED_SPECIFIED_REASONS,
                        TranslateKeys.Match.START_START_FAILED_UNKNOWN_REASONS
                );
            }
            matchConfig = config;
        }

        // 创建比赛对象
        ValueResult<Match> createMatchResult = Match.create(matchConfig);
        if (!(createMatchResult instanceof ValueResult.WithValue(var match))) {
            return createMatchResult.expectValue(
                    TranslateKeys.Match.START_START_FAILED_SPECIFIED_REASONS,
                    TranslateKeys.Match.START_START_FAILED_UNKNOWN_REASONS
            );
        }
        currentMatch = match;

        // 执行比赛开始逻辑，失败时把内层原因挂到“协调器开始比赛失败”键对上
        return currentMatch.start().causedBy(
                TranslateKeys.Match.MATCH_COORDINATOR_START_MATCH_FAILURE_SPECIFIED_CAUSE,
                TranslateKeys.Match.MATCH_COORDINATOR_START_MATCH_FAILURE_UNKNOWN_CAUSE
        );
    }

    /**
     * 使用默认参数开始比赛
     *
     * @see MatchCoordinator#startMatch(MatchConfig)
     */
    public @NonNull OperationResult startMatch() {
        return startMatch(null);
    }

    /**
     * 结束当前进行的比赛<br>
     * 如果当前比赛所选地图是 Bukkit 事件监听器，那么会自动向 Bukkit 注销 它
     *
     * @return 结束结果。比赛不在进行中时返回携带原因的失败
     */
    public @NonNull OperationResult stopMatch() {
        if (getMatchState() != MatchState.IN_PROGRESS) {
            return OperationResult.failure(
                    TranslateKeys.Match.MATCH_COORDINATOR_STOP_MATCH_INVALID_MATCH_STATE
            );
        }

        // 设置标志，指示服务器启动时需要强制结束比赛（防止意外关闭导致状态不一致）
        setAndSaveStopFlag(true);

        // 执行比赛结束逻辑
        if (currentMatch != null) { // 这里理论上不会失败
            this.currentMatch.stop();
        }

        this.currentMatch = null;

        // 重置启动时强制结束的标志
        setAndSaveStopFlag(false);

        return OperationResult.success();
    }

    /**
     * 获取当前进行中比赛的实例
     *
     * @return 当前比赛对象，若无比赛则返回 null
     */
    public @Nullable Match getCurrentMatch() {
        return currentMatch;
    }

    /**
     * 将 {@link PublicFiles#FLAGS} 配置文件中的 {@link FlagsKeys#MATCH_ABORTED} 标志设置为指定值，并保存配置文件
     * */
    private void setAndSaveStopFlag(boolean newValue) {
        Config configInst = Config.getInstance();
        YamlConfig flags = configInst.getYmlConfig(PublicFiles.FLAGS);
        flags.set(FlagsKeys.MATCH_ABORTED, newValue);
        flags.saveAtomic();
    }

    /**
     * 检查比赛是否正在进行<br>
     * 具体来说：如果此比赛的当前状态不为 {@link MatchState#SCHEDULED} 则返回 {@code true}，否则返回 {@code false}
     *
     * @return 比赛目前是否正在进行
     * */
    public boolean matchIsRunning() {
        return this.getMatchState() != MatchState.SCHEDULED;
    }

}
