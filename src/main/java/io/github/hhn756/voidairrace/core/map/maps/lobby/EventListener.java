package io.github.hhn756.voidairrace.core.map.maps.lobby;

import io.github.hhn756.voidairrace.constants.Categories;
import io.github.hhn756.voidairrace.core.map.MapEntry;
import io.github.hhn756.voidairrace.core.playerstatemanager.PlayerStateManager;
import io.github.hhn756.voidairrace.core.playerstatemanager.systems.StateSystem;
import io.github.hhn756.voidairrace.core.playerstatemanager.systems.play.PlayState;
import io.github.hhn756.voidairrace.core.team.TeamRoster;
import io.github.hhn756.voidairrace.core.team.Teams;
import io.github.hhn756.voidairrace.event.ConfigFieldChangeEvent;
import io.github.hhn756.voidairrace.infrastructure.config.file.GameSettingKey;
import io.github.hhn756.voidairrace.infrastructure.config.file.PublicFile;
import io.github.hhn756.voidairrace.infrastructure.registry.Registry;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class EventListener implements Listener {

    @EventHandler
    public void onPlayerOpenContainer(InventoryOpenEvent event) {
        try {
            World eventWorld = event.getPlayer().getWorld();
            if (Data.mapWorld != null && Data.mapWorld.equals(eventWorld)) {
                event.setCancelled(true);
            }
        } catch (NullPointerException ignored) {}
    }

    @EventHandler
    public void onConfigChange(ConfigFieldChangeEvent event) {
        if (event.getFile().filePath().equals(PublicFile.GLOBAL_SETTING.filePath())
                && event.getPath().equals(GameSettingKey.SELECTED_MAP_ID.path())) {
            NamespacedKey newMapId;
            Registry registry = Registry.getInstance();
            String rawMapId = event.getNewValue(GameSettingKey.SELECTED_MAP_ID);
            if (rawMapId != null) {
                newMapId = NamespacedKey.fromString(rawMapId);
            } else {
                // 未选择任何地图将使所有玩家离队
                State.activeTeamArea = 0;
                kickExtraPlayers();
                return;
            }

            // 更新 激活的 队伍选择区域 数量
            try {
                MapEntry mapEntry = registry.category(Categories.MAP).get(newMapId);
                if (mapEntry != null && mapEntry.maxTeams() != null) {
                    State.activeTeamArea = mapEntry.maxTeams(); // 不会是null
                }
            } catch (IllegalArgumentException e) {
                State.activeTeamArea = 0;
            } catch (IllegalStateException ignored) {
                // 在插件启用期间会触发这个事件，这时获取地图注册表实例会抛出这个异常。但这不影响初始化后功能的正常运行
            }

            kickExtraPlayers();
        }
    }

    /**
     * 将 大厅世界中 所有 加入了不被当前所选游戏地图允许的队伍 的玩家踢出队伍
     * */
    private void kickExtraPlayers() {
        if (Data.mapWorld != null) {
            TeamRoster teamRoster = TeamRoster.getInstance();
            for (Player player : Data.mapWorld.getPlayers()) {
                // 跳过 不是 空闲状态 的玩家
                NamespacedKey state = PlayerStateManager.getInstance().getState(player, StateSystem.PLAY.getName());
                if (!PlayState.FREE.getId().equals(state)) continue;

                // 检查 玩家当前加入的队伍 是否是 当前地图 不允许的
                Teams playerTeam = teamRoster.teamToEnum(teamRoster.getTeam(player));
                if (playerTeam != null && playerTeam.ordinal() >= State.activeTeamArea) {
                    teamRoster.leave(player);
                }
            }
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity source = event.getDamager();
        if (source instanceof Player player &&
                source.getLocation().getWorld().equals(Data.mapWorld)) {
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.LEVITATION,
                    60,
                    2
            ));
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.REGENERATION,
                    120,
                    9
            ));
            event.setCancelled(true);
        }
    }
}
