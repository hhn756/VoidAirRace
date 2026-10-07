package io.github.hhn756.voidairrace.service.arena;

import io.github.hhn756.voidairrace.result.OperationResult;
import io.github.hhn756.voidairrace.result.ValueResult;
import org.bukkit.World;
import org.jspecify.annotations.NonNull;

/**
 * 借用竞技场的凭据<br>
 * 借用者有义务保管返回变量，并在用完后归还
 */
public class ArenaToken {
    private final Integer arenaId;
    private final Integer uid;
    private final ArenaManager arenaManager;

    /**
     * package-private 构造器，仅 ArenaManager 可实例化
     */
    ArenaToken(Integer arenaId, Integer uid) {
        this.arenaId = arenaId;
        this.uid = uid;
        this.arenaManager = ArenaManager.getInstance();
    }

    /**
     * 获取竞技场 ID
     */
    public @NonNull Integer getArenaId() {
        return arenaId;
    }

    /**
     * 获取此借据的 uid
     */
    public @NonNull Integer getUid() {
        return uid;
    }

    /**
     * 凭此借据归还竞技场
     *
     * @see ArenaManager#returnArena(ArenaToken)
     */
    public @NonNull OperationResult returnArena() {
        return arenaManager.returnArena(this);
    }

    /**
     * 获取借据对应的竞技场世界
     *
     * @see ArenaManager#getTokenWorld(ArenaToken)
     */
    public @NonNull ValueResult<World> getWorld() {
        return arenaManager.getTokenWorld(this);
    }

    /**
     * 将指定竞技场数据加载到当前借据对应的竞技场世界中
     *
     * @param arenaPath 要加载的竞技场世界数据路径（{@code resource/<arenaPath>/}）
     *
     * @return 加载结果
     *
     * @see ArenaManager#loadArena(ArenaToken, String)
     */
    public @NonNull OperationResult loadArena(String arenaPath) {
        return arenaManager.loadArena(this, arenaPath);
    }

    /**
     * 加载当前借据对应的竞技场世界（不加载具体竞技场数据）
     *
     * @return 加载结果
     *
     * @see ArenaManager#loadArenaWorld(ArenaToken)
     */
    public @NonNull OperationResult loadArenaWorld() {
        return arenaManager.loadArenaWorld(this);
    }

    /**
     * 卸载当前借据对应的竞技场世界（不保存内存中的修改）
     *
     * @return 卸载结果
     *
     * @see ArenaManager#unloadArenaWorld(ArenaToken)
     */
    public @NonNull OperationResult unloadArenaWorld() {
        return arenaManager.unloadArenaWorld(this);
    }
}
