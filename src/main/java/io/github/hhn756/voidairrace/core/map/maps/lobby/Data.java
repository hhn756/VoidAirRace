package io.github.hhn756.voidairrace.core.map.maps.lobby;

import io.github.hhn756.voidairrace.constants.Plugin;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;

class Data {
    static final NamespacedKey MAP_ID = Plugin.key("lobby");
    static final World mapWorld = Bukkit.getWorld("world");
}
