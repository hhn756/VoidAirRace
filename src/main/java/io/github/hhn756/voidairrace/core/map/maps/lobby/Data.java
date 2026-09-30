package io.github.hhn756.voidairrace.core.map.maps.lobby;

import io.github.hhn756.voidairrace.constants.Plugin;
import io.github.hhn756.voidairrace.core.team.Teams;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;

import java.util.HashMap;

class Data {
    static final NamespacedKey MAP_ID = Plugin.key("lobby");
    static final World mapWorld = Bukkit.getWorld("world");

    static final HashMap<BoundingBox, TeamArea> regionToTeam = new HashMap<>();

    static {
        double firstStartX = 27.0;
        double teamAreaStartY = 64.0;
        double teamAreaStartZ = 42.0;
        double teamAreaStartX;
        double xOffset = 0d;
        int areaId = 1;
        for (Teams team: Teams.values()) {
            teamAreaStartX = firstStartX + xOffset;
            BoundingBox region = new BoundingBox(
                    teamAreaStartX, teamAreaStartY, teamAreaStartZ,
                    teamAreaStartX - 4, teamAreaStartY + 4, teamAreaStartZ + 4
            );
            regionToTeam.put(region, new TeamArea(team, areaId));
            xOffset -= 7;
            areaId++;
        }
    }
}
