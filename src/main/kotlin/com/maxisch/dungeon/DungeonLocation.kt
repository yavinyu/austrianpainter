package com.maxisch.dungeon

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.AABB
import net.minecraft.world.scores.DisplaySlot

/**
 * Where on Hypixel the player is, as far as the paint scope cares: which Catacombs floor, and
 * whether they are standing in that floor's boss room.
 *
 * Everything is read off the client's own scoreboard once a tick instead of intercepting packets,
 * so this needs no mixins. Hypixel puts the sidebar text in the team prefix/suffix of each score
 * holder, which is why the lines are assembled from teams rather than read directly.
 */
object DungeonLocation {

    private const val SIDEBAR_OBJECTIVE = "SBScoreboard"

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

    val isMaster: Boolean
        get() = floor?.startsWith("M") == true

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
            val number = floorNumber ?: 0
            inBoss = forcedBoss || (number in 1..7 && BOSS_BOUNDS[number - 1].contains(player.position()))
            return
        }

        val scoreboard = level.scoreboard
        val sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR)
        if (sidebar == null) return reset()

        inSkyblock = sidebar.name == SIDEBAR_OBJECTIVE
        if (!inSkyblock) return reset()

        // The floor only ever appears once, and only while actually inside the dungeon - the queue
        // line mentions The Catacombs too, hence the explicit exclusion.
        var found: String? = null
        for (entry in scoreboard.listPlayerScores(sidebar)) {
            val team = scoreboard.getPlayerTeam(entry.owner()) ?: continue
            val text = clean(team.playerPrefix.string + team.playerSuffix.string)
            if (!text.contains("The Catacombs (") || text.contains("Queue")) continue
            found = text.substringAfter("(").substringBefore(")")
            break
        }

        if (found == null) {
            inDungeon = false
            floor = null
            floorNumber = null
            inBoss = false
            return
        }

        inDungeon = true
        floor = found
        floorNumber = found.lastOrNull()?.digitToIntOrNull() ?: 0

        val number = floorNumber ?: 0
        inBoss = number in 1..7 && BOSS_BOUNDS[number - 1].contains(player.position())
    }

    fun reset() {
        inSkyblock = false
        inDungeon = false
        floor = null
        floorNumber = null
        inBoss = false
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
