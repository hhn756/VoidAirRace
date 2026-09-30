package io.github.hhn756.voidairrace.core.playerstatemanager.systems.play;

import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.core.match.MatchCoordinator;
import io.github.hhn756.voidairrace.core.match.basecomponents.contestant.ContestantComp;
import io.github.hhn756.voidairrace.core.playerstatemanager.PlayerState;
import io.github.hhn756.voidairrace.core.playerstatemanager.PlayerStateManager;
import io.github.hhn756.voidairrace.event.MatchOverEvent;
import io.github.hhn756.voidairrace.infrastructure.config.Config;
import io.github.hhn756.voidairrace.infrastructure.config.files.GlobalSettingKeys;
import io.github.hhn756.voidairrace.infrastructure.config.files.PublicFiles;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * 代表玩家正在参加比赛的状态
 * */
public class Playing implements PlayerState, Listener {
    private static final NamespacedKey stateId = PlayState.PLAYING.getValue();

    @Override
    public NamespacedKey getId() {
        return stateId;
    }

    @Override
    public void onCutin(Player player) {
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().clear();
        player.clearActivePotionEffects();
        player.clearActiveItem();
    }

    // ------ 切出到其他状态 ------

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!PlayerStateManager.getInstance().onState(player, stateId)) return;

        event.setRespawnLocation(
                Config.getInstance().getYmlConfig(PublicFiles.GLOBAL_SETTINGS)
                        .get(GlobalSettingKeys.SPAWN_LOCATION)
        );
        leaveMatch(player);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!PlayerStateManager.getInstance().onState(player, stateId)) return;

        leaveMatch(player);
    }

    @EventHandler
    public void onMatchOver(MatchOverEvent event) {
        event.getMatch().comp(ContestantComp.class).survivingPlayers().forEach(this::leaveMatch);
    }

    private void leaveMatch(Player player) {
        Match currentMatch = MatchCoordinator.getInstance().getCurrentMatch();
        PlayerStateManager.getInstance().toggle(player, PlayState.FREE.getValue());
        if (currentMatch != null) {
            currentMatch.comp(ContestantComp.class).leaveMatch(player);
        }
    }
}
