package com.maxisch.dungeon.detect

import com.maxisch.paint.settings.ApPaths
import net.minecraft.client.Minecraft

/**
 * Which Skyblock island the player is on, as the counterpart to [DungeonLocation]'s "which
 * Catacombs floor, and are we in its boss room".
 *
 * Read off the client's own tab list and scoreboard, so like [DungeonLocation] this needs no mixins
 * and sends nothing - `/locraw` would be more authoritative but would be the first outbound command
 * in the mod.
 *
 * Deliberately **not** latched, unlike [DungeonLocation]'s floor. That latch exists because Hypixel
 * drops the floor line mid-boss and a floor cannot change without a new server instance anyway;
 * islands are the opposite - walking Hub to Dwarven Mines changes the answer with no disconnect, so
 * the poll has to keep running and the newest reading always wins.
 */
object SkyblockLocation {

    /** The tab-list entry Hypixel publishes the island on, e.g. `Area: Dwarven Mines`. */
    private const val AREA_PREFIX = "Area: "

    /** Matches [DungeonLocation]'s poll: the scans below walk every tab-list entry and, at worst,
     *  every scoreboard team, which is too much to do every tick. */
    private const val POLL_INTERVAL_MS = 1_000L

    /** The area name exactly as Hypixel wrote it, even when it matches no [SkyblockIsland]. */
    var area: String? = null
        private set

    var island: SkyblockIsland? = null
        private set

    /**
     * Pretends the player is on this island no matter what the client says, so per-island bindings
     * can be built and tested off Hypixel. Session-only, exactly like [DungeonLocation.forcedFloor]:
     * it must not survive into a real lobby.
     */
    var forcedIsland: SkyblockIsland? = null
        private set

    val forced: Boolean
        get() = forcedIsland != null

    private var lastPollMs = 0L

    fun force(island: SkyblockIsland?) {
        forcedIsland = island
        if (island == null) reset()
    }

    /**
     * The stable identifier the paint scope binds to, or null when the island is unknown. A
     * recognised island uses its own [SkyblockIsland.key]; anything else falls back to a slug of
     * the raw area name, so an island this mod has never heard of still gets its own binding
     * instead of sharing the server-wide one.
     */
    fun bindingSlug(): String? {
        val known = forcedIsland ?: island
        if (known != null) return known.key
        val raw = area ?: return null
        return ApPaths.sanitize(raw).lowercase().ifEmpty { null }
    }

    /** Must run after [DungeonLocation.tick]: the Skyblock and dungeon flags it reads are that
     *  tick's. */
    fun tick() {
        forcedIsland?.let {
            island = it
            area = it.areaName
            return
        }

        if (!DungeonLocation.inSkyblock) return reset()

        val now = System.currentTimeMillis()
        if (now - lastPollMs < POLL_INTERVAL_MS) return
        lastPollMs = now

        val found = findTabListArea() ?: fallbackArea()
        area = found
        island = found?.let { SkyblockIsland.byArea(it) }
    }

    /**
     * The authoritative reading: Hypixel publishes the island as its own tab-list entry, one string
     * naming the island itself rather than the sub-area the sidebar shows (the sidebar says
     * `Village` or `Royal Mines`, which would need a hand-maintained sub-area table to map back).
     */
    private fun findTabListArea(): String? {
        val connection = Minecraft.getInstance().connection ?: return null
        for (info in connection.listedOnlinePlayers) {
            val name = info.tabListDisplayName?.string ?: continue
            val text = DungeonLocation.clean(name).trim()
            if (!text.startsWith(AREA_PREFIX)) continue
            return text.removePrefix(AREA_PREFIX).trim().ifEmpty { null }
        }
        return null
    }

    /**
     * With no `Area:` entry to read, the dungeon detection already knows the one island that
     * matters most here, and the sidebar is checked for a line that happens to name an island
     * outright. A sub-area name matches nothing and leaves the island unknown, which is the honest
     * answer - better than guessing an island from a table that drifts every Hypixel update.
     */
    private fun fallbackArea(): String? {
        if (DungeonLocation.inDungeon) return SkyblockIsland.CATACOMBS.areaName

        val scoreboard = Minecraft.getInstance().level?.scoreboard ?: return null
        for (team in scoreboard.playerTeams) {
            val text = DungeonLocation.clean(team.playerPrefix.string + team.playerSuffix.string).trim()
            SkyblockIsland.byArea(text)?.let { return it.areaName }
        }
        return null
    }

    /** Raw tab-list read for `/ap island raw`, so a detection failure can be diagnosed from
     *  chat rather than a log file - the counterpart to [DungeonLocation.debugSidebar]. */
    fun debugTabList(): List<String> {
        val connection = Minecraft.getInstance().connection ?: return listOf("not connected")
        val lines = mutableListOf<String>()
        for (info in connection.listedOnlinePlayers) {
            val name = info.tabListDisplayName?.string ?: continue
            val text = DungeonLocation.clean(name).trim()
            if (text.isNotEmpty()) lines += "\"$text\""
        }
        return lines.ifEmpty { listOf("tab list carries no display names") }
    }

    fun reset() {
        area = null
        island = null
        // Re-arms the poll, so the first tick after a join or a dimension change reads immediately
        // instead of waiting out the interval.
        lastPollMs = 0L
    }
}
