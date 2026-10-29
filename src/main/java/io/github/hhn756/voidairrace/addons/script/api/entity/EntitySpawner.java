package io.github.hhn756.voidairrace.addons.script.api.entity;

import io.github.hhn756.voidairrace.addons.script.MutationAccess;
import io.github.hhn756.voidairrace.addons.script.api.arena.ArenaHandle;
import io.github.hhn756.voidairrace.addons.script.simplevalue.EntityDescriptor;
import io.github.hhn756.voidairrace.addons.script.simplevalue.Position;
import net.sandius.rembulan.LuaRuntimeException;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * 实体生成流程：{@code arena.spawn} 的全部落地细节<br>
 * 描述符在接触世界前已由 {@link EntityDescriptor} 完成语法与能力校验；本类负责
 * 坐标边界、数量上限、区块处理与实际生成。生成参数经服务端的生成前回调写入，
 * 使实体进入世界时即带齐描述符所述状态
 * <p>
 * 目标区块原本未加载时先加载，生成完成后卸载并保存：生成到未加载区块的实体在
 * 区块再次加载前不可访问。画作与朝向在实体已进入世界后设置，放不下时移除该实体
 * 再抛错
 * */
public final class EntitySpawner {
    /** 每个竞技场世界的已加载实体数上限 */
    public static final int MAX_ENTITY_COUNT = 8192;

    /** 静态工具类，不可实例化 */
    private EntitySpawner() {}

    /**
     * 在竞技场世界内生成一个实体
     *
     * @param arena      目标竞技场
     * @param pos        生成坐标（浮点）
     * @param descriptor 实体描述符
     *
     * @return 新实体的 UUID
     *
     * @throws LuaRuntimeException 无修改权、坐标越界、数量达到上限或服务端拒绝生成时抛出
     * */
    public static @NonNull UUID spawn(
            @NonNull ArenaHandle arena,
            @NonNull Position pos,
            @NonNull EntityDescriptor descriptor
    ) {
        MutationAccess.requireArena(arena.pkg(), arena.requireToken());
        World world = arena.requireWorld();
        Location location = location(world, pos);
        checkBounds(world, location, pos);

        EntityType type = descriptor.type();
        Class<? extends Entity> clazz = type.getEntityClass();
        if (clazz == null) {
            throw new LuaRuntimeException(
                    "生成实体失败：实体类型没有对应的实体类：" + type.getKey());
        }
        if (world.getEntityCount() >= MAX_ENTITY_COUNT) {
            throw new LuaRuntimeException(
                    "生成实体失败：竞技场世界实体数已达上限 " + MAX_ENTITY_COUNT);
        }

        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        boolean wasLoaded = world.isChunkLoaded(chunkX, chunkZ);
        if (!wasLoaded) world.getChunkAt(chunkX, chunkZ);

        Entity entity;
        try {
            entity = create(world, location, clazz, descriptor);
            if (entity instanceof Painting painting) {
                applyPainting(painting, descriptor.data());
            }
        } catch (RuntimeException e) {
            // 走到这里时实体要么尚未进入世界（生成前回调抛错），要么已由画作设置处
            // 自行移除，无需在此清理
            throw e;
        } finally {
            if (!wasLoaded) world.getChunkAt(chunkX, chunkZ).unload(true);
        }

        return entity.getUniqueId();
    }

    /**
     * 生成实体并应用描述符中可在进入世界前生效的字段
     *
     * @param world       目标世界
     * @param location    生成坐标
     * @param clazz       实体类
     * @param descriptor  实体描述符
     *
     * @return 已进入世界的实体
     * */
    private static @NonNull Entity create(
            @NonNull World world,
            @NonNull Location location,
            @NonNull Class<? extends Entity> clazz,
            @NonNull EntityDescriptor descriptor
    ) {
        try {
            if (LivingEntity.class.isAssignableFrom(clazz)) {
                return spawnLiving(world, location, clazz.asSubclass(LivingEntity.class),
                        descriptor);
            }
            return world.spawn(location, clazz, entity -> applyBeforeAdd(entity, descriptor));
        } catch (IllegalArgumentException e) {
            throw new LuaRuntimeException(
                    "生成实体失败：" + descriptor.type().getKey() + "（服务端拒绝或生成事件被取消）");
        }
    }

    /** 活体走可关闭随机化的生成重载，避免随机装备、随机幼年等覆盖描述符 */
    private static <T extends LivingEntity> @NonNull Entity spawnLiving(
            @NonNull World world,
            @NonNull Location location,
            @NonNull Class<T> clazz,
            @NonNull EntityDescriptor descriptor
    ) {
        return world.spawn(
                location, clazz, CreatureSpawnEvent.SpawnReason.CUSTOM, false,
                entity -> applyBeforeAdd(entity, descriptor));
    }

    /**
     * 应用进入世界前可写入的描述符字段<br>
     * 画作的画作键与朝向依赖实体在方块网格中的实际位置，另见
     * {@link #applyPainting}
     * */
    private static void applyBeforeAdd(
            @NonNull Entity entity, @NonNull EntityDescriptor descriptor) {
        EntityDescriptor.Data data = descriptor.data();
        if (descriptor.rot() != null) {
            entity.setRotation(descriptor.rot().yaw(), descriptor.rot().pitch());
        }
        if (descriptor.name() != null) {
            entity.customName(descriptor.name());
        }
        Boolean nameVisible = data.nameVisible();
        if (nameVisible == null && descriptor.name() != null) nameVisible = true;
        if (nameVisible != null) entity.setCustomNameVisible(nameVisible);
        if (data.silent() != null) entity.setSilent(data.silent());
        if (data.glowing() != null) entity.setGlowing(data.glowing());
        if (data.invulnerable() != null) entity.setInvulnerable(data.invulnerable());
        if (data.gravity() != null) entity.setGravity(data.gravity());
        entity.setPersistent(data.persistent() == null || data.persistent());

        if (entity instanceof LivingEntity living) {
            if (data.invisible() != null) living.setInvisible(data.invisible());
            if (!data.equipment().isEmpty()) {
                EntityEquipment equipment = living.getEquipment();
                if (equipment == null) {
                    throw new LuaRuntimeException(
                            "该实体没有装备栏，不能设置装备：" + entity.getType().getKey());
                }
                data.equipment().forEach(equipment::setItem);
            }
        }
        if (entity instanceof Mob mob && data.ai() != null) {
            mob.setAware(data.ai());
        }
        if (entity instanceof Ageable ageable && data.baby() != null) {
            if (data.baby()) {
                ageable.setBaby();
            } else {
                ageable.setAdult();
            }
        }
        if (entity instanceof Item item && data.item() != null) {
            item.setItemStack(data.item());
        }
        if (entity instanceof ArmorStand stand) {
            if (data.armorStandArms() != null) stand.setArms(data.armorStandArms());
            if (data.armorStandSmall() != null) stand.setSmall(data.armorStandSmall());
            if (data.armorStandMarker() != null) stand.setMarker(data.armorStandMarker());
            if (data.armorStandBasePlate() != null) stand.setBasePlate(data.armorStandBasePlate());
        }
    }

    /**
     * 设置画作与附着方向：放不下时移除已生成的实体再抛错
     * */
    private static void applyPainting(
            @NonNull Painting painting, EntityDescriptor.Data data) {
        try {
            if (data.facing() != null && !painting.setFacingDirection(data.facing(), false)) {
                throw new LuaRuntimeException("生成画失败：指定方向放不下画作");
            }
            if (data.art() != null && !painting.setArt(data.art(), false)) {
                throw new LuaRuntimeException(
                        "生成画失败：画作 " + org.bukkit.Registry.ART.getKey(data.art())
                                + " 在该位置放不下");
            }
        } catch (LuaRuntimeException e) {
            painting.remove();
            throw e;
        }
    }

    /** 浮点坐标 → 世界内的 {@link Location} */
    private static @NonNull Location location(@NonNull World world, @NonNull Position pos) {
        return new Location(world, pos.x(), pos.y(), pos.z());
    }

    /**
     * 校验坐标在该竞技场世界内：X/Z 在世界边界内，Y 在世界高度范围内
     * */
    private static void checkBounds(
            @NonNull World world, @NonNull Location location, @NonNull Position pos) {
        if (!Double.isFinite(pos.x()) || !Double.isFinite(pos.y()) || !Double.isFinite(pos.z())) {
            throw new LuaRuntimeException("生成实体失败：坐标必须是有限数字");
        }
        if (!world.getWorldBorder().isInside(location)) {
            throw new LuaRuntimeException("生成实体失败：坐标超出竞技场世界边界");
        }
        if (pos.y() < world.getMinHeight() || pos.y() >= world.getMaxHeight()) {
            throw new LuaRuntimeException(
                    "生成实体失败：Y 坐标超出世界高度范围（"
                            + world.getMinHeight() + " ~ " + (world.getMaxHeight() - 1) + "）");
        }
    }
}
