package io.github.hhn756.voidairrace.core.playerstatemanager.systems.play;

import io.github.hhn756.voidairrace.core.match.Match;
import io.github.hhn756.voidairrace.core.match.basecomponents.contestant.ContestantComp;
import io.github.hhn756.voidairrace.core.playerstatemanager.DefaultState;
import io.github.hhn756.voidairrace.core.playerstatemanager.PlayerState;
import io.github.hhn756.voidairrace.core.playerstatemanager.PlayerStateManager;
import io.github.hhn756.voidairrace.core.team.TeamRoster;
import io.github.hhn756.voidairrace.event.MatchStartedEvent;
import io.github.hhn756.voidairrace.event.PlayerJoinMatchEvent;
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
 * 代表玩家目前空闲中，没有参加比赛的状态
 * */
public class Free implements PlayerState, DefaultState, Listener {
    private static final NamespacedKey stateId = PlayState.FREE.getValue();

    @Override
    public NamespacedKey getId() {
        return stateId;
    }

    @Override
    public void onCutin(Player player) {
        player.setGameMode(GameMode.ADVENTURE);
        player.teleport(
                Config.getInstance().getYmlConfig(PublicFiles.GLOBAL_SETTINGS)
                        .get(GlobalSettingKeys.SPAWN_LOCATION)
        );
        TeamRoster.getInstance().leave(player);
        player.getInventory().clear();
        player.clearActivePotionEffects();
        player.clearActiveItem();
    }

    // ------ 切出到其他状态 ------

    @EventHandler
    public void onPlayerJoinMatch(PlayerJoinMatchEvent event) {
        PlayerStateManager playerStateManager = PlayerStateManager.getInstance();
        Player player = event.getPlayer();
        if (!playerStateManager.onState(player, stateId)) return;
        playerStateManager.toggle(player, PlayState.PLAYING.getValue());
    }

    @EventHandler
    public void onMatchStarted(MatchStartedEvent event) {
        Match match = event.getMatch();
        PlayerStateManager playerStateManager = PlayerStateManager.getInstance();
        match.configOf(ContestantComp.CONFIG_KEY)
                .initialContestants()
                .forEach(contestant -> {
                    if (!PlayerStateManager.getInstance().onState(contestant, stateId)) return;
                    playerStateManager.toggle(contestant, PlayState.PLAYING.getValue());
                });
    }

    // ------ 其他内部行为 ------

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!PlayerStateManager.getInstance().onState(event.getPlayer(), stateId)) return;
        event.getPlayer().teleport(
                Config.getInstance().getYmlConfig(PublicFiles.GLOBAL_SETTINGS)
                        .get(GlobalSettingKeys.SPAWN_LOCATION)
        );
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (!PlayerStateManager.getInstance().onState(event.getPlayer(), stateId)) return;
        event.setRespawnLocation(
                Config.getInstance().getYmlConfig(PublicFiles.GLOBAL_SETTINGS)
                        .get(GlobalSettingKeys.SPAWN_LOCATION)
        );
    }
}
