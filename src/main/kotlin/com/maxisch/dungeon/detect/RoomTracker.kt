package com.maxisch.dungeon.detect

import com.maxisch.paint.settings.ApSettings
import com.maxisch.paint.rule.BossZones
import com.maxisch.paint.rule.DeviceColumns
import com.maxisch.paint.PaintStorage
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import com.maxisch.dungeon.room.RoomScope

/** Drives the dungeon subsystem once a tick and hands [PaintStorage] the room in scope. */
object RoomTracker {

    /** Private on purpose: [PaintStorage.scope] is the one every other package reads. */
    private var scope: RoomScope? = null

    private var wasInDungeon = false

    /** Edge-detects [DeviceColumns.shouldApply] so the view is only rebuilt when it actually flips. */
    private var deviceActive = false

    /** Same edge-detection, for [BossZones.shouldApply]. */
    private var zoneActive = false

    /** Edge-detects the floor, which decides which boss preset and room presets are loaded. */
    private var lastFloor: Int? = null

    /** Last [RoomScanner.layoutVersion] the paint side was told about. */
    private var lastLayout = RoomScanner.layoutVersion

    /** Edge-detects [ApSettings.dungeonRoomScope], which shows or hides every room's paint at once. */
    private var roomScopeActive = ApSettings.dungeonRoomScope

    fun tick() {
        DungeonLocation.tick()

        if (DungeonLocation.inDungeon) {
            wasInDungeon = true
        } else if (wasInDungeon) {
            wasInDungeon = false
            RoomScanner.reset()
        }

        val roomScope = ApSettings.dungeonRoomScope
        if (roomScope != roomScopeActive) {
            roomScopeActive = roomScope
            PaintStorage.onRoomVisibilityChanged()
        }

        val floor = DungeonLocation.floorNumber.takeIf { DungeonLocation.inDungeon }
        if (floor != lastFloor) {
            lastFloor = floor
            PaintStorage.onDungeonFloorChanged(floor)
        }

        val device = DeviceColumns.shouldApply()
        if (device != deviceActive) {
            deviceActive = device
            PaintStorage.onDeviceScopeChanged()
        }

        val zones = BossZones.shouldApply()
        if (zones != zoneActive) {
            zoneActive = zones
            PaintStorage.onDeviceScopeChanged()
        }

        val next = resolve()
        if (next != scope) {
            scope = next
            PaintStorage.onScopeChanged(next)
        }

        // After resolve(), which is what drives the scan: a room that just found its marker can be
        // projected into the index now, and nothing else would notice it appearing.
        if (RoomScanner.layoutVersion != lastLayout) {
            lastLayout = RoomScanner.layoutVersion
            PaintStorage.onRoomLayoutChanged()
        }
    }

    /**
     * Drops everything detected and tells the paint side the scope is gone with it - clearing only
     * the local field would leave [PaintStorage] pointing at a room on a server we have left.
     */
    fun reset() {
        DungeonLocation.reset()
        RoomScanner.reset()
        wasInDungeon = false
        // Re-arms the edge, so joining a fresh instance inside the arena still fires it.
        deviceActive = false
        zoneActive = false
        lastFloor = null
        // Deliberately not re-synced: RoomScanner.reset() has bumped the version, so the next tick
        // fires the layout hook and drops projections belonging to the run that just ended.

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
        return RoomScanner.scopeFor(room)
    }
}
