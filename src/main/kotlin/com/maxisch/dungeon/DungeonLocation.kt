package com.maxisch.dungeon

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Scoreboard

/**
 * Where on Hypixel the player is, as far as the paint scope cares: which Catacombs floor, and
 * whether they are standing in that floor's boss room.
 *
 * Everything is read off the client's own scoreboard instead of intercepting packets, so this needs
 * no mixins. Hypixel still puts the sidebar text in each team's prefix/suffix, but a score entry's
 * owner name does not reliably correlate back to its team, so every team on the scoreboard is
 * scanned directly for its text instead of looking teams up per sidebar entry.
 *
 * The floor is read **once per server** and then latched. Hypixel drops the "The Catacombs (M7)"
 * line from the sidebar partway through some boss fights, and taking that as "left the dungeon"
 * unloaded the boss preset mid-fight and threw away the scanned layout with it. A floor cannot
 * change without a new server instance anyway, so only a join, a dimension change or actually
 * leaving Skyblock clears it - never the sidebar text. The boss test stays per tick because it is
 * positional and has to flip when the player walks in.
 */
object DungeonLocation {

    private const val SIDEBAR_OBJECTIVE = "SBScoreboard"

    /** How often the sidebar is scanned for the floor while it is still unknown. */
    private const val POLL_INTERVAL_MS = 1_000L

    /** How long Hypixel's sidebar may be gone before the latch is taken as "left Skyblock". */
    private const val SKYBLOCK_GRACE_MS = 5_000L

    var inSkyblock: Boolean = false
        private set

    var inDungeon: Boolean = false
        private set

    /** `"F7"`, `"M7"`, `"E"`… exactly as Hypixel writes it, or null outside a dungeon. */
    var floor: String? = null
        private set

    var floorNumber: Int? = null
        private set

    var inBoss: Boolean = false
        private set

    /**
     * Pretends the player is on this floor no matter what the scoreboard says, so the feature can
     * be built and tested on a server that does not send Hypixel's sidebar. Null is the normal
     * detected behaviour. Deliberately not persisted: it must not survive into a real dungeon.
     */
    var forcedFloor: String? = null
        private set

    /** With [forcedFloor] set, treats the player as inside the boss room wherever they stand. */
    var forcedBoss: Boolean = false
        private set

    val forced: Boolean
        get() = forcedFloor != null

    /** The floor has been read off the sidebar and is being held for the rest of this server. */
    var latched: Boolean = false
        private set

    private var lastSkyblockMs = 0L
    private var lastPollMs = 0L

    fun force(floor: String?, boss: Boolean = false) {
        forcedFloor = floor
        forcedBoss = boss
        if (floor == null) reset()
    }

    fun tick() {
        val client = Minecraft.getInstance()
        val level = client.level
        val player = client.player
        if (level == null || player == null) return reset()

        forcedFloor?.let { forcedName ->
            inSkyblock = true
            inDungeon = true
            floor = forcedName
            floorNumber = forcedName.lastOrNull()?.digitToIntOrNull() ?: 0
            updateBoss(player)
            if (forcedBoss) inBoss = true
            return
        }

        // One map lookup and a string compare, cheap enough to do every tick. The per-team scan
        // below is the expensive part, and that is what the latch skips.
        val sidebar = level.scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR)
        val skyblock = sidebar != null && sidebar.name == SIDEBAR_OBJECTIVE
        val now = System.currentTimeMillis()
        if (skyblock) lastSkyblockMs = now

        inSkyblock = skyblock

        if (latched) {
            // The sidebar text is deliberately ignored for the rest of the run; only losing
            // Skyblock's board altogether, for long enough that it cannot be a hiccup, counts as
            // having left.
            if (!skyblock && now - lastSkyblockMs > SKYBLOCK_GRACE_MS) return reset()
            updateBoss(player)
            return
        }

        if (!skyblock) return reset()

        if (now - lastPollMs < POLL_INTERVAL_MS) return
        lastPollMs = now

        // Outside a dungeon this simply keeps finding nothing - the hub and the queue are the
        // normal case for it, and the poll interval is what keeps that affordable.
        val found = findFloor(level.scoreboard)
        if (found == null) {
            inDungeon = false
            floor = null
            floorNumber = null
            inBoss = false
            return
        }

        latched = true
        inDungeon = true
        floor = found
        floorNumber = found.lastOrNull()?.digitToIntOrNull() ?: 0
        updateBoss(player)
    }

    /**
     * The floor line's score entry does not reliably correlate back to its team by owner name, so
     * every team is scanned directly for its text instead - same approach NoammAddons uses. The
     * floor only ever appears once, and only while actually inside the dungeon - the queue line
     * mentions The Catacombs too, hence the explicit exclusion.
     */
    private fun findFloor(scoreboard: Scoreboard): String? {
        for (team in scoreboard.playerTeams) {
            val text = clean(team.playerPrefix.string + team.playerSuffix.string)
            if (!text.contains("The Catacombs (") || text.contains("Queue")) continue
            return text.substringAfter("(").substringBefore(")")
        }
        return null
    }

    private fun updateBoss(player: Player) {
        val number = floorNumber ?: 0
        inBoss = number in 1..7 && BOSS_BOUNDS[number - 1].contains(player.position())
    }

    /**
     * Raw scoreboard read for `/paintbrush room raw`, so a detection failure can be diagnosed from
     * chat instead of needing a log file: shows the objective Hypixel is actually sending and every
     * team's cleaned prefix+suffix text, independent of whether it matches "The Catacombs (".
     */
    fun debugSidebar(): List<String> {
        val scoreboard = Minecraft.getInstance().level?.scoreboard ?: return listOf("no level loaded")
        val sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR)
            ?: return listOf("no sidebar objective displayed")

        val lines = mutableListOf("objective name: ${sidebar.name}")
        for (team in scoreboard.playerTeams) {
            lines += "\"" + clean(team.playerPrefix.string + team.playerSuffix.string) + "\""
        }
        return lines
    }

    fun reset() {
        inSkyblock = false
        inDungeon = false
        floor = null
        floorNumber = null
        inBoss = false
        // Re-arms the sidebar scan. The timestamps are left alone; both are re-stamped by the next
        // read that finds Skyblock's board.
        latched = false
    }

    /** Strips section-sign formatting and the invisible padding Hypixel pads sidebar lines with. */
    private fun clean(raw: String): String = buildString(raw.length) {
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            when {
                char == '§' -> index++
                char.code in 32..126 -> append(char)
            }
            index++
        }
    }

    /**
     * Boss room bounds per floor, F1 first. Boss rooms sit at fixed coordinates, which is why they
     * need no scanning and why their paint can be stored as plain world coordinates.
     */
    private val BOSS_BOUNDS = arrayOf(
        AABB(-14.0, 55.0, 49.0, -72.0, 146.0, -40.0),
        AABB(-40.0, 99.0, -40.0, 24.0, 54.0, 59.0),
        AABB(-40.0, 118.0, -40.0, 42.0, 64.0, 37.0),
        AABB(-40.0, 112.0, -40.0, 50.0, 53.0, 47.0),
        AABB(-40.0, 112.0, -8.0, 50.0, 53.0, 118.0),
        AABB(-40.0, 51.0, -8.0, 22.0, 110.0, 134.0),
        AABB(-8.0, 0.0, -8.0, 134.0, 254.0, 147.0),
    )
}
