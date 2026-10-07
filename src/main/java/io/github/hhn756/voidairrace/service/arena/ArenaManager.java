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
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * 管理多个竞技场的加载/卸载，每个竞技场用数字 ID 标识（{@code 1} ~ {@code maxArenas}）<br>
 * 竞技场世界目录的生命周期与借用一致：加载竞技场数据时经冲突检查后由模板复制创建，
 * 归还/卸载世界时删除；加载前目录不应存在，存在即异常残留或同名的无关目录，拒绝加载
 */
public class ArenaManager implements Module {
    private static ArenaManager instance;

    /**
     * 竞技场世界目录名的合法形态（由{@link #arenaIdToWorldName(Integer)}生成），<br>
     * 删除目录前校验，杜绝误删其他目录
     * */
    private static final Pattern ARENA_WORLD_DIR_NAME = Pattern.compile(
            Pattern.quote(Plugin.ns) + "\\.arena\\.\\d+");

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
     * 将指定竞技场数据加载到竞技场世界中<br>
     * 竞技场世界目录已存在时返回失败（上次异常退出残留或同名的无关目录，不擅自删除，交由管理员处置）
     *
     * @param token 此借据对应的竞技场世界将要承载竞技场数据
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
        } catch (ArenaException e) {
            // 无可上报原因的翻译键：用户消息取异常携带的用户文案，技术性消息取异常文本，兜底文案交给最外层
            return OperationResult.failure(null, e.getUserMessage(), e.getMessage(), e);
        }
        return OperationResult.success();
    }

    /**
     * 加载竞技场世界<br>
     * 要求竞技场世界目录已存在（由{@link #loadArena(ArenaToken, String)}复制模板时创建），否则返回失败
     *
     * @param token 加载 此借据对应的竞技场世界
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
     * 如果世界未加载，那么会自动加载它（要求竞技场世界目录已就绪，见{@link #loadArena(ArenaToken, String)}）
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
     * 如果竞技场世界已加载，那么会重新加载它；如果竞技场世界未加载，那么会加载它<br>
     * 正常生命周期下竞技场世界目录在归还/卸载时已删除，加载前不应存在；
     * 此时仍存在即上次异常退出的残留或同名的无关目录，拒绝加载并记录日志，不擅自删除
     *
     * @param arenaWorldId 要将竞技场加载到的竞技场世界
     * @param arenaPath 要加载的竞技场世界数据路径（{@code resource/<arenaPath>/}）
     *
     * @throws IOException 复制竞技场数据时出现 IO 错误则抛出
     * @throws ArenaException 竞技场世界目录已存在（拒绝覆盖）时抛出
     * */
    private void loadArena(Integer arenaWorldId, String arenaPath) throws IOException {
        // 如果该竞技场世界已加载，先卸载（不保存修改，竞技场世界目录随卸载一并删除）
        if (arenaState(arenaWorldId).getLoadedWorld() != null) {
            unloadArenaWorld(arenaWorldId);
        }

        String worldName = arenaIdToWorldName(arenaWorldId);
        Path worldDir = Path.of(worldName);

        // 冲突检查：目录在归还/卸载时已删除，仍存在即残留或同名无关目录，拒绝覆盖
        if (Files.exists(worldDir)) {
            String techDetail = "竞技场世界目录已存在，拒绝覆盖：" + worldDir.toAbsolutePath();
            VoidAirRace.getInstance().getLogger().warning(techDetail);
            throw new ArenaException(techDetail,
                    Component.translatable(TranslateKeys.Arena.ARENA_MANAGER_DIR_CONFLICT));
        }

        // 复制竞技场数据
        JarEntryUtil.copyFromJar(arenaPath, worldName);

        // 加载世界
        loadArenaWorld(arenaWorldId);
    }

    /**
     * 加载指定竞技场世界（竞技场世界目录须已存在，由{@link #loadArena(Integer, String)}复制模板时创建）<br>
     * 如果尝试重复加载同一世界，那么不会执行任何操作
     *
     * @param arenaId 竞技场 ID（{@code 1} ~ {@code maxArenas}）
     *
     * @throws ArenaException 当竞技场世界目录不存在或世界加载失败时抛出
     */
    private World loadArenaWorld(Integer arenaId) throws ArenaException {
        // 已加载直接返回，否则加载新世界
        World loadedWorld = arenaState(arenaId).getLoadedWorld();
        if (loadedWorld != null) return loadedWorld;

        String worldName = arenaIdToWorldName(arenaId);
        Path worldDir = Path.of(worldName);

        // 竞技场世界目录缺失说明模板未复制，拒绝凭空创建空世界
        if (!Files.isDirectory(worldDir)) {
            String techDetail = "竞技场世界目录不存在，无法加载竞技场世界：" + worldDir.toAbsolutePath();
            VoidAirRace.getInstance().getLogger().warning(techDetail);
            throw new ArenaException(techDetail,
                    Component.translatable(TranslateKeys.Arena.ARENA_MANAGER_DIR_MISSING));
        }

        // 添加提示文件
        addTips(worldName);

        // 创建并加载世界；比赛期间不自动落盘（卸载时同样不保存）
        World newWorld = WorldCreatorUtil.createVoidWorld(worldName);
        newWorld.setAutoSave(false);
        arenaState(arenaId).setLoadedWorld(newWorld);
        return newWorld;
    }

    /**
     * 卸载指定竞技场世界，并且不保存内存中的修改<br>
     * 卸载成功后删除竞技场世界目录（目录生命周期与借用一致：加载时创建、归还/卸载时删除）；
     * 世界本就未加载时不做任何事（目录可能是同名的无关目录，不擅自删除）
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

        // 卸载世界（不保存）；卸载成功才删除目录，避免删除仍在使用中的世界目录
        boolean unloaded = Boolean.TRUE.equals(Bukkit.unloadWorld(world, false));
        if (!unloaded) {
            VoidAirRace.getInstance().getLogger().severe(
                    "卸载竞技场世界失败，暂不删除其世界目录：" + arenaIdToWorldName(arenaId));
            return;
        }
        deleteWorldDirectory(Path.of(arenaIdToWorldName(arenaId)));

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
     * 在世界目录中添加提示文件（目录须已存在，由竞技场数据复制时创建）
     * */
    private void addTips(String targetDir) {
        VoidAirRace mainClass = VoidAirRace.getInstance();
        File targetDirFile = new File(targetDir);
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
     * 递归删除一个竞技场世界目录<br>
     * 目录名必须匹配竞技场世界命名模式（{@link #ARENA_WORLD_DIR_NAME}），否则拒绝删除；
     * 删除失败只记录日志不抛出（调用方无法补救），残留目录会在下次加载时被冲突检查拦下
     *
     * @param dir 竞技场世界目录
     * */
    private static void deleteWorldDirectory(@NonNull Path dir) {
        if (!ARENA_WORLD_DIR_NAME.matcher(dir.getFileName().toString()).matches()) {
            VoidAirRace.getInstance().getLogger().severe(
                    "目标目录名不符合竞技场世界命名模式，拒绝删除：" + dir.toAbsolutePath());
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    VoidAirRace.getInstance().getLogger().log(
                            Level.SEVERE, "删除竞技场世界文件失败：" + path.toAbsolutePath(), e);
                }
            });
        } catch (IOException e) {
            VoidAirRace.getInstance().getLogger().log(
                    Level.SEVERE, "遍历竞技场世界目录失败：" + dir.toAbsolutePath(), e);
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
