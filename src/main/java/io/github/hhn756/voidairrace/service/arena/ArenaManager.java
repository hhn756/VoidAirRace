package io.github.hhn756.voidairrace.service.arena;

import io.github.hhn756.voidairrace.Voidairrace;
import io.github.hhn756.voidairrace.constants.Plugin;
import io.github.hhn756.voidairrace.constants.TranslateKeys;
import io.github.hhn756.voidairrace.exception.ArenaException;
import io.github.hhn756.voidairrace.infrastructure.config.Config;
import io.github.hhn756.voidairrace.infrastructure.config.YamlConfig;
import io.github.hhn756.voidairrace.infrastructure.config.file.GameSettingKey;
import io.github.hhn756.voidairrace.infrastructure.config.file.GlobalSettingKey;
import io.github.hhn756.voidairrace.infrastructure.config.file.PublicFile;
import io.github.hhn756.voidairrace.infrastructure.language.ServerLanguage;
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
import org.jspecify.annotations.Nullable;

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
 * 管理多个竞技场的加载/卸载，每个竞技场用数字 ID 标识（{@code 1} ~ {@link GameSettingKey#MAX_ARENAS}）<br>
 * 竞技场世界目录的生命周期与借用一致：加载竞技场数据时经冲突检查后由模板复制创建，
 * 归还/卸载世界时删除；加载前目录不应存在，存在即异常残留或同名的无关目录，拒绝加载
 */
public class ArenaManager implements Module {
    private static @Nullable ArenaManager instance;

    /**
     * 竞技场世界目录名的合法形态（由{@link #arenaIdToWorldName(Integer)}生成），<br>
     * 删除目录前校验，杜绝误删其他目录
     * */
    private static final @NonNull Pattern ARENA_WORLD_DIR_NAME = Pattern.compile(
            Pattern.quote(Plugin.ns) + "\\.arena\\.\\d+");

    @Override
    public @NonNull Collection<@NonNull Class<? extends Module>> getRequiredModules() {
        return List.of(Config.class);
    }

    /**
     * 插件启用时执行<br>
     * 读取配置等有副作用的工作必须在此完成：Modules 会先实例化全部模块，再按拓扑顺序加载
     * */
    private void onLoad() {
        Voidairrace mainClass = Voidairrace.getInstance();

        // 读取最大竞技场数量，若配置项不存在或无效则使用默认值
        YamlConfig gameSettings = Config.getInstance().getYmlConfig(PublicFile.GAME_SETTING);
        this.maxArenas = gameSettings.get(GameSettingKey.MAX_ARENAS, 16);
        if (maxArenas < 1) {
            mainClass.getLogger().warning(
                    ServerLanguage.translate(TranslateKeys.Log.ARENA_MAX_ARENAS_INVALID));
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

    public static @NonNull ArenaManager getInstance() throws NullPointerException {
        if (instance == null) throw new NullPointerException("竞技场管理器实例不存在");
        return instance;
    }

    // ----------

    /**
     * 最大竞技场数量
     * */
    private @NonNull Integer maxArenas = 0;

    /**
     * 提示语列表
     * */
    private static final @NonNull String[] tips = {
            "Don't put your files here!",
            "别把你的文件放在这里！",
            "別把你的文件放在這裡！",
            "ここにあなたのファイルを置かないでください！",
            "Не клади свои документы сюда!"
    };

    private final @NonNull HashMap<@NonNull Integer, @NonNull ArenaState> arenaStates = new HashMap<>();

    /**
     * 借据 uid，递增
     * */
    private @NonNull Integer nextTokenUid = 1;

    /**
     * 未借出的竞技场数量
     * */
    private @NonNull Integer freeCount = 0;

    private ArenaManager() {}

    // ------ 借用竞技场世界 ------

    /**
     * 借用一个竞技场世界<br>
     * 借入者标识用于审计与无空闲时的占用提示；无空闲时失败消息列出各占用者
     *
     * @param borrower 借入者标识（用户包 Id 或内置地图名）
     * */
    public @NonNull ValueResult<ArenaToken> borrow(@NonNull String borrower) {
        Integer freeArenaId = getFreeArena();
        if (freeArenaId == -1) {
            String occupants = arenaStates.values().stream()
                    .filter(ArenaState::isBorrowed)
                    .map(state -> "竞技场#" + state.getActiveTokenUid() + "(" + state.getBorrower() + ")")
                    .collect(java.util.stream.Collectors.joining("、"));
            return ValueResult.failure(
                    TranslateKeys.Arena.ARENA_MANAGER_NO_FREE_ARENA,
                    "无空闲竞技场（共 " + maxArenas + " 个），占用者：" + occupants
            );
        }

        // 更新状态
        ArenaState arenaState = arenaState(freeArenaId);
        arenaState.setBorrowed(true);
        arenaState.setBorrower(borrower);
        arenaState.setMatchArea(false);
        Integer tokenUid = nextTokenUid++;
        arenaState.setActiveTokenUid(tokenUid);
        freeCount--;

        return ValueResult.success(
                new ArenaToken(freeArenaId, tokenUid)
        );
    }

    /**
     * 凭借据归还一个竞技场世界<br>
     * 已登记为比赛区域的借据拒绝手动归还（归还职责在比赛结束流程，见
     * {@link #markMatchArea(ArenaToken)}）
     *
     * @param token 借据
     *
     * @return 如果成功归还将返回成功的结果，如果借据无效或已登记为比赛区域则返回失败的结果
     * */
    public @NonNull OperationResult returnArena(@NonNull ArenaToken token) {
        if (!validateToken(token)) return OperationResult.failure(
                TranslateKeys.Arena.ARENA_MANAGER_TOKEN_IS_INVALID,
                tokenInvalidTechDetail(token)
        );

        ArenaState arenaState = arenaState(token.getArenaId());
        if (arenaState.isMatchArea()) return OperationResult.failure(
                null,
                null,
                "竞技场 " + token.getArenaId() + " 已登记为比赛区域，由比赛结束流程归还，不能手动归还",
                null
        );

        // 卸载世界
        unloadArenaWorld(token.getArenaId());

        // 更新状态
        arenaState.setBorrowed(false);
        arenaState.setBorrower(null);
        arenaState.setActiveTokenUid(null);
        freeCount++;

        return OperationResult.success();
    }

    /**
     * 将借据登记为比赛区域：登记后手动归还通道关闭，归还职责移交比赛结束流程
     * （组件卸载时归还，归还前先经{@link #unmarkMatchArea(ArenaToken)}解除登记）<br>
     * 双归还防线从句柄层内移至此：不管归还请求来自哪条路径，已登记借据一律被
     * {@link #returnArena(ArenaToken)}拒绝
     *
     * @param token 借据
     * */
    public void markMatchArea(@NonNull ArenaToken token) {
        arenaState(token.getArenaId()).setMatchArea(true);
    }

    /**
     * 解除借据的比赛区域登记（归还职责回到手动通道）<br>
     * 供比赛结束流程在归还前调用；解除后借据可正常归还
     *
     * @param token 借据
     * */
    public void unmarkMatchArea(@NonNull ArenaToken token) {
        arenaState(token.getArenaId()).setMatchArea(false);
    }

    // ------ 对竞技场的操作 ------

    /**
     * 将指定竞技场数据加载到竞技场世界中<br>
     * 竞技场世界目录已存在时返回失败（上次异常退出残留或同名的无关目录，不擅自删除，交由管理员处置）
     *
     * @param token 此借据对应的竞技场世界将要承载竞技场数据
     * @param arenaPath 要加载的竞技场世界数据路径（{@code resource/<arenaPath>/}）
     * */
    public @NonNull OperationResult loadArena(@NonNull ArenaToken token, @NonNull String arenaPath) {
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
     * 将指定竞技场数据从包内模板目录加载到竞技场世界中<br>
     * 与{@link #loadArena(ArenaToken, String)}（插件 jar 内模板）不同，本方法从
     * 已挂载的包资产目录复制模板（目录形式包为普通目录，zip 形式包为挂载文件系统内的目录）
     *
     * @param token       此借据对应的竞技场世界将要承载竞技场数据
     * @param templateDir 包内世界模板目录（须存在且为目录）
     *
     * @return 加载结果
     * */
    public @NonNull OperationResult loadArena(@NonNull ArenaToken token, @NonNull Path templateDir) {
        if (!validateToken(token)) return OperationResult.failure(
                TranslateKeys.Arena.ARENA_MANAGER_TOKEN_IS_INVALID,
                tokenInvalidTechDetail(token)
        );

        try {
            loadArena(token.getArenaId(), templateDir);
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
    public @NonNull OperationResult loadArenaWorld(@NonNull ArenaToken token) {
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
    public @NonNull OperationResult unloadArenaWorld(@NonNull ArenaToken token) {
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
     * 查询借据当前登记的借入者
     *
     * @param token 借据
     *
     * @return 借入者标识（用户包 Id 或内置地图名）；借据无效时返回{@code null}
     * */
    public @Nullable String borrowerOf(@NonNull ArenaToken token) {
        if (!validateToken(token)) return null;
        return arenaState(token.getArenaId()).getBorrower();
    }

    /**
     * 判断一个世界是否为竞技场世界<br>
     * 按命名模式判断（{@link #ARENA_WORLD_DIR_NAME}）：世界对象在竞技场世界加载期间、
     * 卸载之前都算竞技场世界，不受状态表中已加载世界字段的更新时机影响
     *
     * @param world 待判断的世界
     *
     * @return 是竞技场世界时为{@code true}
     * */
    public boolean isArenaWorld(@NonNull World world) {
        return ARENA_WORLD_DIR_NAME.matcher(world.getName()).matches();
    }

    /**
     * 查询一个竞技场世界当前登记的借入者
     *
     * @param world 待查询的世界
     *
     * @return 借入者标识（用户包 Id 或内置地图名）；不是竞技场世界、世界未登记在
     *         任何竞技场状态上或竞技场未借出时返回{@code null}
     * */
    public @Nullable String borrowerOfWorld(@NonNull World world) {
        if (!isArenaWorld(world)) return null;
        for (Integer id = 1; id <= maxArenas; id++) {
            ArenaState state = arenaState(id);
            if (state.getLoadedWorld() == world) return state.getBorrower();
        }
        return null;
    }

    /**
     * 获取最大竞技场数量
     * */
    public @NonNull Integer getMaxArenas() {
        return maxArenas;
    }

    /**
     * 获取还未被借出的竞技场数量
     * */
    public @NonNull Integer getFreeCount() {
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
    private void loadArena(@NonNull Integer arenaWorldId, @NonNull String arenaPath) throws IOException {
        Path worldDir = prepareWorldDir(arenaWorldId);

        // 复制竞技场数据
        JarEntryUtil.copyFromJar(arenaPath, worldDir.toString());

        // 加载世界
        loadArenaWorld(arenaWorldId);
    }

    /**
     * 加载一个竞技场（不是竞技场世界），模板来自包内目录<br>
     * 生命周期约束与插件 jar 模板路径（{@link #loadArena(Integer, String)}）完全一致
     *
     * @param arenaWorldId 要将竞技场加载到的竞技场世界
     * @param templateDir 包内世界模板目录（可为 zip 挂载文件系统内的目录）
     *
     * @throws IOException 复制竞技场数据时出现 IO 错误则抛出
     * @throws ArenaException 竞技场世界目录已存在（拒绝覆盖）时抛出
     * */
    private void loadArena(@NonNull Integer arenaWorldId, @NonNull Path templateDir) throws IOException {
        Path worldDir = prepareWorldDir(arenaWorldId);

        // 复制竞技场数据
        copyDirTree(templateDir, worldDir);

        // 加载世界
        loadArenaWorld(arenaWorldId);
    }

    /**
     * 准备竞技场世界目录：已加载的世界先卸载（不保存修改），残留/无关目录拒绝覆盖<br>
     * 两条模板来源路径（插件 jar / 包资产）共享的加载前置
     *
     * @param arenaWorldId 要准备目录的竞技场世界
     *
     * @return 已确认不存在的竞技场世界目录（模板数据的复制目标）
     *
     * @throws ArenaException 竞技场世界目录已存在（拒绝覆盖）时抛出
     * */
    private @NonNull Path prepareWorldDir(@NonNull Integer arenaWorldId) throws ArenaException {
        // 如果该竞技场世界已加载，先卸载（不保存修改，竞技场世界目录随卸载一并删除）
        if (arenaState(arenaWorldId).getLoadedWorld() != null) {
            unloadArenaWorld(arenaWorldId);
        }

        String worldName = arenaIdToWorldName(arenaWorldId);
        Path worldDir = Path.of(worldName);

        // 冲突检查：目录在归还/卸载时已删除，仍存在即残留或同名无关目录，拒绝覆盖
        if (Files.exists(worldDir)) {
            String techDetail = ServerLanguage.translate(
                    TranslateKeys.Log.ARENA_WORLD_DIR_CONFLICT, worldDir.toAbsolutePath());
            Voidairrace.getInstance().getLogger().warning(techDetail);
            throw new ArenaException(techDetail,
                    Component.translatable(TranslateKeys.Arena.ARENA_MANAGER_DIR_CONFLICT));
        }
        return worldDir;
    }

    /**
     * 递归复制目录树（源可位于任意已挂载文件系统，含 zip 挂载点）<br>
     * 目标目录由冲突检查保证不存在，按源相对结构逐级创建
     *
     * @param source 源目录
     * @param target 目标目录（须不存在）
     *
     * @throws IOException 任一文件复制失败时抛出
     * */
    private static void copyDirTree(@NonNull Path source, @NonNull Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path entry : paths.toList()) {
                Path dest = target.resolve(source.relativize(entry).toString());
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(entry, dest);
                }
            }
        }
    }

    /**
     * 加载指定竞技场世界（竞技场世界目录须已存在，由{@link #loadArena(Integer, String)}复制模板时创建）<br>
     * 如果尝试重复加载同一世界，那么不会执行任何操作
     *
     * @param arenaId 竞技场 ID（{@code 1} ~ {@code maxArenas}）
     *
     * @throws ArenaException 当竞技场世界目录不存在或世界加载失败时抛出
     */
    private @NonNull World loadArenaWorld(@NonNull Integer arenaId) throws ArenaException {
        // 已加载直接返回，否则加载新世界
        World loadedWorld = arenaState(arenaId).getLoadedWorld();
        if (loadedWorld != null) return loadedWorld;

        String worldName = arenaIdToWorldName(arenaId);
        Path worldDir = Path.of(worldName);

        // 竞技场世界目录缺失说明模板未复制，拒绝凭空创建空世界
        if (!Files.isDirectory(worldDir)) {
            String techDetail = ServerLanguage.translate(
                    TranslateKeys.Log.ARENA_WORLD_DIR_MISSING, worldDir.toAbsolutePath());
            Voidairrace.getInstance().getLogger().warning(techDetail);
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
    private void unloadArenaWorld(@NonNull Integer arenaId) {
        World world = arenaState(arenaId).getLoadedWorld();
        if (world == null) return;

        // 将世界内的所有玩家传送回出生点
        Location spawnLoc = Config.getInstance()
                .getYmlConfig(PublicFile.GLOBAL_SETTING)
                .get(GlobalSettingKey.SPAWN_LOCATION);
        for (Player player : world.getPlayers()) {
            player.teleport(spawnLoc);
        }

        // 卸载世界（不保存）；卸载成功才删除目录，避免删除仍在使用中的世界目录
        boolean unloaded = Boolean.TRUE.equals(Bukkit.unloadWorld(world, false));
        if (!unloaded) {
            Voidairrace.getInstance().getLogger().severe(ServerLanguage.translate(
                    TranslateKeys.Log.ARENA_UNLOAD_FAILED, arenaIdToWorldName(arenaId)));
            return;
        }
        deleteWorldDirectory(Path.of(arenaIdToWorldName(arenaId)));

        // 更新状态
        arenaState(arenaId).setLoadedWorld(null);
    }

    /**
     * @return 储存竞技场状态的对象
     * */
    private @NonNull ArenaState arenaState(@NonNull Integer arenaId) {
        return arenaStates.get(arenaId);
    }

    /**
     * @return 空闲竞技场的 ID，如果没有空闲的竞技场则返回 {@code -1}
     */
    private @NonNull Integer getFreeArena() {
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
    private @NonNull String arenaIdToWorldName(@NonNull Integer arenaId) {
        return Plugin.ns + ".arena." + arenaId;
    }

    /**
     * 在世界目录中添加提示文件（目录须已存在，由竞技场数据复制时创建）
     * */
    private void addTips(@NonNull String targetDir) {
        Voidairrace mainClass = Voidairrace.getInstance();
        File targetDirFile = new File(targetDir);
        for (String tipText : tips) {
            File tipFile = new File(targetDirFile, tipText);
            try {
                tipFile.createNewFile();
            } catch (IOException e) {
                mainClass.getLogger().warning(ServerLanguage.translate(
                        TranslateKeys.Log.ARENA_TIP_FILE_CREATE_FAILED, targetDir, tipText, e.getMessage()));
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
            Voidairrace.getInstance().getLogger().severe(ServerLanguage.translate(
                    TranslateKeys.Log.ARENA_DELETE_DIR_NAME_MISMATCH, dir.toAbsolutePath()));
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    Voidairrace.getInstance().getLogger().log(
                            Level.SEVERE, ServerLanguage.translate(
                                    TranslateKeys.Log.ARENA_DELETE_FILE_FAILED, path.toAbsolutePath()), e);
                }
            });
        } catch (IOException e) {
            Voidairrace.getInstance().getLogger().log(
                    Level.SEVERE, ServerLanguage.translate(
                            TranslateKeys.Log.ARENA_WALK_DIR_FAILED, dir.toAbsolutePath()), e);
        }
    }

    /**
     * 检查借据是否有效
     *
     * @return {@code true} 表示有效，{@code false} 表示无效
     * */
    private @NonNull Boolean validateToken(@NonNull ArenaToken token) {
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
