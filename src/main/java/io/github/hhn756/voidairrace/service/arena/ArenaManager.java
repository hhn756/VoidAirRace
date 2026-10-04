package io.github.hhn756.voidairrace.service.arena;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.constants.Plugin;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.exception.ArenaException;
import io.github.hhn756.voidairrace.infrastructure.config.Config;
import io.github.hhn756.voidairrace.infrastructure.config.YamlConfig;
import io.github.hhn756.voidairrace.infrastructure.config.files.GameSettingKeys;
import io.github.hhn756.voidairrace.infrastructure.config.files.GlobalSettingKeys;
import io.github.hhn756.voidairrace.infrastructure.config.files.PublicFiles;
import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import io.github.hhn756.voidairrace.infrastructure.util.JarEntryUtil;
import io.github.hhn756.voidairrace.infrastructure.util.world.WorldCreatorUtil;
import io.github.hhn756.voidairrace.result.OperationResult;
import io.github.hhn756.voidairrace.result.ValueResult;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

/**
 * 管理多个竞技场的加载/卸载，每个竞技场用数字 ID 标识（{@code 1} ~ {@code maxArenas}）
 */
public class ArenaManager implements Module {
    private static ArenaManager instance;

    @Override
    public Collection<Class<? extends Module>> getRequiredModules() {
        return List.of(Config.class);
    }

    /**
     * 插件启用时执行<br>
     * 读取配置等有副作用的工作必须在此完成：Modules 会先实例化全部模块，再按拓扑顺序加载
     * */
    private void onLoad() {
        VoidAirRace mainClass = VoidAirRace.getInstance();

        // 读取最大竞技场数量，若配置项不存在或无效则使用默认值
        YamlConfig gameSettings = Config.getInstance().getYmlConfig(PublicFiles.GAME_SETTINGS);
        this.maxArenas = gameSettings.get(GameSettingKeys.MAX_ARENAS, 16);
        if (maxArenas < 1) {
            mainClass.getLogger().warning("配置中的最大竞技场数量小于 1，已强制改为 1");
            maxArenas = 1;
        }

        // 初始化
        for (Integer id = 1; id <= maxArenas; id++) {
            arenaStates.put(id, new ArenaState());
        }
        freeCount = maxArenas;

        instance = this;
    }

    /** 插件禁用时执行 */
    private void onUnload() {
        instance = null;
    }

    public static ArenaManager getInstance() throws NullPointerException {
        if (instance == null) throw new NullPointerException("竞技场管理器实例不存在");
        return instance;
    }

    // ----------

    /**
     * 最大竞技场数量
     * */
    private Integer maxArenas;

    /**
     * 提示语列表
     * */
    private static final String[] tips = {
            "Don't put your files here!",
            "别把你的文件放在这里！",
            "別把你的文件放在這裡！",
            "ここにあなたのファイルを置かないでください！",
            "Не клади свои документы сюда!"
    };

    private final HashMap<Integer, ArenaState> arenaStates = new HashMap<>();

    /**
     * 借据 uid，递增
     * */
    private Integer nextTokenUid = 1;

    /**
     * 未借出的竞技场数量
     * */
    private Integer freeCount;

    private ArenaManager() {}

    // ------ 借用竞技场世界 ------

    /**
     * 借用一个竞技场世界
     * */
    public @NonNull ValueResult<ArenaToken> borrow() {
        Integer freeArenaId = getFreeArena();
        if (freeArenaId == -1) return ValueResult.failure(
                TranslateKeys.Arena.ARENA_MANAGER_NO_FREE_ARENA,
                "无空闲竞技场（共 " + maxArenas + " 个）"
        );

        // 更新状态
        ArenaState arenaState = arenaState(freeArenaId);
        arenaState.setBorrowed(true);
        Integer tokenUid = nextTokenUid++;
        arenaState.setActiveTokenUid(tokenUid);
        freeCount--;

        return ValueResult.success(
                new ArenaToken(freeArenaId, tokenUid)
        );
    }

    /**
     * 凭借据归还一个竞技场世界
     *
     * @param token 借据
     *
     * @return 如果成功归还将返回成功的结果，如果借据无效则返回失败的结果
     * */
    public @NonNull OperationResult returnArena(@NonNull ArenaToken token) {
        if (!validateToken(token)) return OperationResult.failure(
                TranslateKeys.Arena.ARENA_MANAGER_TOKEN_IS_INVALID,
                tokenInvalidTechDetail(token)
        );

        // 卸载世界
        unloadArenaWorld(token.getArenaId());

        // 更新状态
        ArenaState arenaState = arenaState(token.getArenaId());
        arenaState.setBorrowed(false);
        arenaState.setActiveTokenUid(null);
        freeCount++;

        return OperationResult.success();
    }

    // ------ 对竞技场的操作 ------

    /**
     * 将指定竞技场数据加载到竞技场世界中
     *
     * @param token 此借据都应的竞技场世界将要承载竞技场数据
     * @param arenaPath 要加载的竞技场世界数据路径（{@code resource/<arenaPath>/}）
     * */
    public @NonNull OperationResult loadArena(ArenaToken token, String arenaPath) {
        if (!validateToken(token)) return OperationResult.failure(
                TranslateKeys.Arena.ARENA_MANAGER_TOKEN_IS_INVALID,
                tokenInvalidTechDetail(token)
        );

        try {
            loadArena(token.getArenaId(), arenaPath);
        } catch (IOException e) {
            return OperationResult.failure(
                    TranslateKeys.Arena.ARENA_MANAGER_IO_EXCEPTION, null, null, e
            );
        }
        return OperationResult.success();
    }

    /**
     * 加载竞技场世界
     *
     * @param token 加载此借据对应的竞技场世界
     *
     * @see ArenaException
     * */
    public @NonNull OperationResult loadArenaWorld(ArenaToken token) {
        if (!validateToken(token)) return OperationResult.failure(
                TranslateKeys.Arena.ARENA_MANAGER_TOKEN_IS_INVALID,
                tokenInvalidTechDetail(token)
        );

        try {
            loadArenaWorld(token.getArenaId());
        } catch (ArenaException e) {
            // 无可上报原因的翻译键：用户消息取异常携带的用户文案，技术性消息取异常文本，兜底文案交给最外层
            return OperationResult.failure(null, e.getUserMessage(), e.getMessage(), e);
        }
        return OperationResult.success();
    }

    /**
     * 卸载竞技场世界（不保存内存中的修改）
     * */
    public @NonNull OperationResult unloadArenaWorld(ArenaToken token) {
        if (!validateToken(token)) return OperationResult.failure(
                TranslateKeys.Arena.ARENA_MANAGER_TOKEN_IS_INVALID,
                tokenInvalidTechDetail(token)
        );

        unloadArenaWorld(token.getArenaId());
        return OperationResult.success();
    }

    /**
     * 获取借据对应的竞技场世界<br>
     * 如果世界未加载，那么会自动加载它
     * */
    public @NonNull ValueResult<World> getTokenWorld(@NonNull ArenaToken token) {
        if (!validateToken(token)) return ValueResult.failure(
                TranslateKeys.Arena.ARENA_MANAGER_TOKEN_IS_INVALID,
                tokenInvalidTechDetail(token)
        );

        loadArenaWorld(token.getArenaId());
        return ValueResult.success(
                arenaState(token.getArenaId()).getLoadedWorld()
        );
    }

    // ---------- 状态查询 ----------

    /**
     * 获取最大竞技场数量
     * */
    public Integer getMaxArenas() {
        return maxArenas;
    }

    /**
     * 获取还未被借出的竞技场数量
     * */
    public Integer getFreeCount() {
        return freeCount;
    }

    // ---------- 内部辅助方法 ----------

    /**
     * 加载一个竞技场（不是竞技场世界）<br>
     * 如果竞技场世界 已加载，那么会 重新加载 它；如果竞技场世界 未加载，那么会 加载 它
     *
     * @param arenaWorldId 要将竞技场加载到的竞技场世界
     * @param arenaPath 要加载的竞技场世界数据路径（{@code resource/<arenaPath>/}）
     *
     * @throws IOException 复制竞技场数据时出现 IO 错误则抛出
     * */
    private void loadArena(Integer arenaWorldId, String arenaPath) throws IOException {
        // 如果该竞技场世界已加载，先卸载（不保存修改）
        if (arenaState(arenaWorldId).getLoadedWorld() != null) {
            unloadArenaWorld(arenaWorldId);
        }

        String worldName = arenaIdToWorldName(arenaWorldId);

        // 复制竞技场数据
        JarEntryUtil.copyFromJar(arenaPath, worldName);

        // 加载世界
        loadArenaWorld(arenaWorldId);
    }

    /**
     * 加载指定竞技场世界<br>
     * 如果尝试重复加载同一世界，那么不会执行任何操作
     *
     * @param arenaId 竞技场 ID（{@code 1} ~ {@code maxArenas}）
     *
     * @throws ArenaException 当竞技场复制失败或世界加载失败时抛出
     */
    private World loadArenaWorld(Integer arenaId) throws ArenaException {
        // 已加载直接返回，否则加载新世界
        World loadedWorld = arenaState(arenaId).getLoadedWorld();
        if (loadedWorld != null) return loadedWorld;

        String worldName = arenaIdToWorldName(arenaId);

        // 添加提示文件
        addTips(worldName);

        // 创建并加载世界
        World newWorld = WorldCreatorUtil.createVoidWorld(worldName);
        arenaState(arenaId).setLoadedWorld(newWorld);
        return newWorld;
    }

    /**
     * 卸载指定竞技场世界，并且不保存内存中的修改
     *
     * @param arenaId 竞技场 ID
     */
    private void unloadArenaWorld(Integer arenaId) {
        World world = arenaState(arenaId).getLoadedWorld();
        if (world == null) return;

        // 将世界内的所有玩家传送回出生点
        Location spawnLoc = Config.getInstance()
                .getYmlConfig(PublicFiles.GLOBAL_SETTINGS)
                .get(GlobalSettingKeys.SPAWN_LOCATION);
        for (Player player : world.getPlayers()) {
            player.teleport(spawnLoc);
        }

        // 卸载世界
        Bukkit.unloadWorld(world, false);

        // 更新状态
        arenaState(arenaId).setLoadedWorld(null);
    }

    /**
     * @return 储存竞技场状态的对象
     * */
    private @NonNull ArenaState arenaState(Integer arenaId) {
        return arenaStates.get(arenaId);
    }

    /**
     * @return 空闲竞技场的 ID，如果没有空闲的竞技场则返回 {@code -1}
     */
    private Integer getFreeArena() {
        for (Integer id = 1; id <= maxArenas; id++) {
            if (!arenaState(id).isBorrowed()) {
                return id;
            }
        }
        return -1;
    }

    /**
     *根据竞技场 ID 生成世界文件夹名称
     * */
    private String arenaIdToWorldName(Integer arenaId) {
        return Plugin.ns + ".arena." + arenaId;
    }

    /**
     * 在世界目录中添加提示文件
     * */
    private void addTips(String targetDir) {
        VoidAirRace mainClass = VoidAirRace.getInstance();
        File targetDirFile = new File(targetDir);
        if (!targetDirFile.exists() && !targetDirFile.mkdirs()) {
            mainClass.getLogger().warning("无法创建世界目录：" + targetDir);
            return;
        }
        for (String tipText : tips) {
            File tipFile = new File(targetDirFile, tipText);
            try {
                tipFile.createNewFile();
            } catch (IOException e) {
                mainClass.getLogger().warning("创建提示语文件 '" + tipText + "' 失败");
            }
        }
    }

    /**
     * 检查借据是否有效
     *
     * @return {@code true} 表示有效，{@code false} 表示无效
     * */
    private Boolean validateToken(@NonNull ArenaToken token) {
        Integer activeTokenUid = arenaState(token.getArenaId()).getActiveTokenUid();
        return token.getUid().equals(activeTokenUid);
    }

    /**
     * @return “借据无效”失败结果的技术性消息，记录借据指认的竞技场 id、借据 uid 与当前生效的 uid
     *         （服务端参数，禁止显示给玩家）
     * */
    private @NonNull String tokenInvalidTechDetail(@NonNull ArenaToken token) {
        return "借据无效：arenaId=" + token.getArenaId()
                + ", 借据uid=" + token.getUid()
                + ", 生效uid=" + arenaState(token.getArenaId()).getActiveTokenUid();
    }
}
