package io.github.hhn756.voidairrace.core.map;

import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.result.OperationResult;
import org.jetbrains.annotations.Range;
import org.jspecify.annotations.NonNull;

/**
 * 可游玩（可被比赛使用）的游戏地图的基类<br>
 * 注：每局比赛都会使用不同地图实例，如需跨对局共享数据可以使用静态属性或其他类来储存数据
 * */
public abstract class PlayableGameMap extends GameMap {
    /**
     * @return 地图是否已准备好开始游戏
     * */
    public abstract boolean isReady();

    /**
     * 在 使用此地图的比赛 开始时执行<br>
     * 如果返回的结果{@link OperationResult#isSuccess()}返回{@code false}会导致地图组件启用失败和比赛开始失败
     *
     * @return 开始结果。失败时携带地图自身给出的原因键，由地图组件与比赛开始流程逐层包装
     * */
    public @NonNull OperationResult start(@NonNull Match match) {
        return OperationResult.success();
    }

    /**
     * 地图的每游戏刻回调<br>
     * 使用此地图的比赛进行期间由地图组件逐刻调用（主线程，比赛开始后启动、结束时停止）；
     * 默认空实现，地图可在此处理位置轮询、计时等连续逻辑——这类逻辑不适合事件
     * <p>
     * 调用频率固定为每刻一次，实现方自行避免耗时操作
     *
     * @param match 使用此地图的比赛
     * */
    public void tick(@NonNull Match match) {}

    /**
     * 在 使用此地图的比赛 结束时进行
     * */
    public @NonNull OperationResult over(@NonNull Match match) {
        return OperationResult.success();
    };

    /**
     * @return 地图允许参赛的最大队伍数量
     * */
    @Range(from = 1, to = Integer.MAX_VALUE)
    public abstract int maxTeams();
}
