package com.maxisch.dungeon

import com.maxisch.paint.ApSettings
import com.maxisch.paint.PaintStorage
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos

/**
 * Which room's paint is currently live.
 *
 * A scope is only handed out once the room is oriented, so paint can never bind to a room that is
 * still half scanned and end up stored against the wrong corner.
 */
data class RoomScope(val key: String, val origin: BlockPos, val rotation: Int)

object RoomTracker {

    var scope: RoomScope? = null
        private set

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

    fun reset() {
        DungeonLocation.reset()
        RoomScanner.reset()
        wasInDungeon = false
        scope = null
    }

    private fun resolve(): RoomScope? {
        if (!ApSettings.dungeonRoomScope) return null
        if (!DungeonLocation.inDungeon) return null

        val floor = DungeonLocation.floorNumber ?: return null
        // Master mode shares the floor's boss config; the layouts are identical.
        if (DungeonLocation.inBoss) return RoomScope("B$floor", BlockPos.ZERO, 0)

        RoomScanner.tick()

        val player = Minecraft.getInstance().player ?: return null
        val room = RoomScanner.roomAt(player.position()) ?: return null
        val origin = room.clayPos ?: return null
        val rotation = room.rotation ?: return null
        return RoomScope(room.name, origin, 360 - rotation)
    }
}
