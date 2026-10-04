package io.github.hhn756.voidairrace.infrastructure.util.world.blockfinder;

import io.github.hhn756.voidairrace.VoidAirRace;
import io.github.hhn756.voidairrace.infrastructure.modules.Module;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

/**
 * 在指定区域内搜索符合条件的方块的工具
 */
public class BlockFinder implements Module {

    /**
     * 插件实例，插件启用时由本模块的 {@code onLoad} 设置
     */
    public static Plugin plugin;

    private BlockFinder() {}

    @Override
    public Collection<Class<? extends Module>> getRequiredModules() {
        return List.of();
    }

    /** 插件启用时执行 */
    private void onLoad() {
        plugin = VoidAirRace.getInstance();
    }

    /** 插件禁用时执行 */
    private void onUnload() {
    }

    /**
     * 将区块的 X 坐标和 Z 坐标合并成一个 long 类型的唯一标识符
     *
     * @param chunkX 区块 X 坐标
     * @param chunkZ 区块 Z 坐标
     *
     * @return 区块的唯一标识符
     * */
    private static long encodeChunk(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
    }

    /**
     * 异步查找指定范围内所有符合条件的方块（使用 BlockData 条件）
     *
     * @param world        目标世界
     * @param minX         最小X坐标（包含）
     * @param minY         最小Y坐标（包含）
     * @param minZ         最小Z坐标（包含）
     * @param maxX         最大X坐标（包含）
     * @param maxY         最大Y坐标（包含）
     * @param maxZ         最大Z坐标（包含）
     * @param condition    方块数据条件（在异步线程中安全使用）
     * @param plugin       插件实例
     * @return CompletableFuture，返回符合条件的所有Location坐标列表
     */
    public static CompletableFuture<List<Location>> findBlocksAsync(
            World world,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            Predicate<BlockData> condition,
            Plugin plugin) {

        if (world == null || condition == null) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        int realMinX = Math.min(minX, maxX);
        int realMaxX = Math.max(minX, maxX);
        int realMinY = Math.min(minY, maxY);
        int realMaxY = Math.max(minY, maxY);
        int realMinZ = Math.min(minZ, maxZ);
        int realMaxZ = Math.max(minZ, maxZ);

        int minChunkX = realMinX >> 4;
        int maxChunkX = realMaxX >> 4;
        int minChunkZ = realMinZ >> 4;
        int maxChunkZ = realMaxZ >> 4;

        // 在主线程获取所有相关区块的快照
        Map<Long, org.bukkit.ChunkSnapshot> snapshotMap = new HashMap<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                org.bukkit.Chunk chunk = world.getChunkAt(cx, cz);
                org.bukkit.ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, false, false);
                snapshotMap.put(encodeChunk(cx, cz), snapshot);
            }
        }

        Executor executor = (plugin != null) ?
                (runnable) -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, runnable) :
                CompletableFuture::runAsync;

        return CompletableFuture.supplyAsync(() -> {
            List<Location> result = new ArrayList<>();
            for (int x = realMinX; x <= realMaxX; x++) {
                int chunkX = x >> 4;
                int localX = x & 15;
                for (int y = realMinY; y <= realMaxY; y++) {
                    for (int z = realMinZ; z <= realMaxZ; z++) {
                        int chunkZ = z >> 4;
                        int localZ = z & 15;
                        org.bukkit.ChunkSnapshot snapshot = snapshotMap.get(encodeChunk(chunkX, chunkZ));
                        if (snapshot == null) {
                            continue;
                        }
                        // 使用 getBlockData 替代 getBlockState
                        BlockData blockData = snapshot.getBlockData(localX, y, localZ);
                        if (condition.test(blockData)) {
                            result.add(new Location(world, x, y, z));
                        }
                    }
                }
            }
            return result;
        }, executor);
    }

    /**
     * 异步查找指定区域内符合条件的方块，返回这些方块的坐标<br>
     * 便捷方法，使用 Material 类型判断
     *
     * @param world        世界
     * @param minX,minY,minZ 最小坐标
     * @param maxX,maxY,maxZ 最大坐标
     * @param materialType 要查找的方块材质
     *
     * @return 异步结果
     *
     * @see BlockFinder#findBlocksAsync(World, int, int, int, int, int, int, Predicate, Plugin)
     */
    public static CompletableFuture<List<Location>> findBlocksByMaterialAsync(
            World world,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            org.bukkit.Material materialType) {
        return findBlocksAsync(
                world,
                minX, minY, minZ,
                maxX, maxY, maxZ,
                blockData -> blockData.getMaterial() == materialType,
                plugin
        );
    }
}
