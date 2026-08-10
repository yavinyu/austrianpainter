package com.maxisch.dungeon

import com.maxisch.paint.ApSettings
import com.maxisch.paint.PaintStorage
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos

/** Drives the dungeon subsystem once a tick and hands [PaintStorage] the room in scope. */
object RoomTracker {

    /** Private on purpose: [PaintStorage.scope] is the one every other package reads. */
    private var scope: RoomScope? = null

    private var wasInDungeon = false

    fun tick() {
        DungeonLocation.tick()

        if (DungeonLocation.inDungeon) {
            wasInDungeon = true
        } else if (wasInDungeon) {
            wasInDungeon = false
            RoomScanner.reset()
        }

        val next = resolve()
        if (next == scope) return
        scope = next
        PaintStorage.onScopeChanged(next)
    }

    /**
     * Drops everything detected and tells the paint side the scope is gone with it - clearing only
     * the local field would leave [PaintStorage] pointing at a room on a server we have left.
     */
    fun reset() {
        DungeonLocation.reset()
        RoomScanner.reset()
        wasInDungeon = false

        val had = scope != null
        scope = null
        if (had) PaintStorage.onScopeChanged(null)
    }

    /**
     * The two corners of the dungeon room the player is standing in, so the area screen can select
     * it in one click instead of flying to opposite corners.
     *
     * Null outside a room, in a boss room (those are fixed coordinates and need no box), and for an
     * L-shaped room - see [ScannedRoom.corners]. The floor is the bottom of the world: the scan
     * knows the roof but never probes for a floor, and [com.maxisch.paint.session.PaintArea] clamps
     * whatever it is given.
     */
    fun currentRoomCorners(): Pair<BlockPos, BlockPos>? {
        if (scope?.isBoss != false) return null

        val client = Minecraft.getInstance()
        val level = client.level ?: return null
        val player = client.player ?: return null
        return RoomScanner.roomAt(player.position())?.corners(level.minY)
    }

    private fun resolve(): RoomScope? {
        if (!ApSettings.dungeonRoomScope) return null
        if (!DungeonLocation.inDungeon) return null

        val floor = DungeonLocation.floorNumber ?: return null
        // Master mode shares the floor's boss config; the layouts are identical.
        if (DungeonLocation.inBoss) return RoomScope("B$floor", BlockPos.ZERO, 0, isBoss = true)

        RoomScanner.tick()

        val player = Minecraft.getInstance().player ?: return null
        val room = RoomScanner.roomAt(player.position()) ?: return null
        val origin = room.clayPos ?: return null
        val rotation = room.rotation ?: return null
        return RoomScope(room.name, origin, 360 - rotation, isBoss = false)
    }
}
