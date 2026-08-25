package com.maxisch.dungeon.detect

/**
 * The Skyblock islands the mod knows by name, matched against the area string Hypixel publishes -
 * `Area: Dwarven Mines` in the tab list, or the island's own name on the sidebar.
 *
 * Deliberately free of Minecraft imports so the name matching can be unit tested without a client
 * bootstrap, the same way [com.maxisch.paint.settings.ApPaths] already is.
 *
 * The list is not a gate: an area Hypixel adds later simply misses every entry here, and
 * [SkyblockLocation.bindingSlug] falls back to a slug of the raw name, so a new island still gets
 * its own stable preset binding rather than silently collapsing into the server-wide one.
 */
enum class SkyblockIsland(val key: String, val areaName: String) {
    PRIVATE_ISLAND("private_island", "Private Island"),
    GARDEN("garden", "Garden"),
    HUB("hub", "Hub"),
    THE_FARMING_ISLANDS("farming_islands", "The Farming Islands"),
    THE_PARK("the_park", "The Park"),
    SPIDERS_DEN("spiders_den", "Spider's Den"),
    THE_END("the_end", "The End"),
    CRIMSON_ISLE("crimson_isle", "Crimson Isle"),
    KUUDRAS_HOLLOW("kuudras_hollow", "Kuudra's Hollow"),
    GOLD_MINE("gold_mine", "Gold Mine"),
    DEEP_CAVERNS("deep_caverns", "Deep Caverns"),
    DWARVEN_MINES("dwarven_mines", "Dwarven Mines"),
    CRYSTAL_HOLLOWS("crystal_hollows", "Crystal Hollows"),
    MINESHAFT("mineshaft", "Mineshaft"),
    GALATEA("galatea", "Galatea"),
    JERRYS_WORKSHOP("jerrys_workshop", "Jerry's Workshop"),
    BACKWATER_BAYOU("backwater_bayou", "Backwater Bayou"),
    DARK_AUCTION("dark_auction", "Dark Auction"),
    DUNGEON_HUB("dungeon_hub", "Dungeon Hub"),
    CATACOMBS("catacombs", "Catacombs"),
    THE_RIFT("the_rift", "The Rift"),
    ;

    companion object {

        /** Case-insensitive because the sidebar and the tab list do not always capitalise a name
         *  the same way; whitespace-tolerant because Hypixel pads its lines. */
        fun byArea(area: String): SkyblockIsland? {
            val wanted = area.trim()
            return entries.firstOrNull { it.areaName.equals(wanted, ignoreCase = true) }
        }

        /** The name `/ap island <name>` accepts, matching either the enum [key] or the
         *  display [areaName]. */
        fun byKeyOrArea(name: String): SkyblockIsland? {
            val wanted = name.trim()
            return entries.firstOrNull { it.key.equals(wanted, ignoreCase = true) } ?: byArea(wanted)
        }
    }
}
