package com.maxisch.dungeon

import com.maxisch.dungeon.DungeonGrid.DOOR_ROOF_HEIGHTS
import com.maxisch.dungeon.DungeonGrid.ROOM_SIZE
import com.maxisch.dungeon.DungeonGrid.START_X
import com.maxisch.dungeon.DungeonGrid.START_Z
import com.maxisch.dungeon.DungeonGrid.index
import net.minecraft.world.phys.Vec3

/**
 * Rebuilds the Catacombs room layout from the blocks around the player.
 *
 * A room is identified by hashing a column of blocks through its centre (see [WorldProbe.coreHash])
 * and looking that hash up in [RoomDataStore], and it is oriented by finding the single blue
 * terracotta block Hypixel leaves at one corner of every room's roof.
 *
 * This is a trimmed port of NoammAddons' `DungeonScanner`/`UniqueRoom`: no map rendering, no
 * secrets, no score tracking - only what identifying and orienting a room needs. The grid geometry
 * lives in [DungeonGrid], the world reads in [WorldProbe], and a prefab's tiles in [ScannedRoom].
 */
object RoomScanner {

    private const val RESCAN_DELAY_MS = 250L

    private val grid = arrayOfNulls<Tile>(DungeonGrid.SIZE * DungeonGrid.SIZE)
    private val rooms = LinkedHashMap<String, ScannedRoom>()

    private var lastScan = 0L
    private var complete = false

    fun reset() {
        grid.fill(null)
        rooms.clear()
        complete = false
        lastScan = 0L
    }

    /**
     * Throttled; once every cell is resolved only the rooms still missing their marker are
     * revisited, which is a handful of block reads rather than a full grid sweep.
     */
    fun tick() {
        if (!RoomDataStore.loaded) return
        val now = System.currentTimeMillis()
        if (now - lastScan < RESCAN_DELAY_MS) return
        lastScan = now

        if (complete) {
            rooms.values.forEach(ScannedRoom::findRotation)
            return
        }
        scan()
    }

    internal fun roomAt(pos: Vec3): ScannedRoom? {
        val (column, row) = DungeonGrid.cellAt(pos)
        return (grid[index(row, column)] as? RoomTile)?.room
    }

    private fun scan() {
        var allLoaded = true

        for (column in 0 until DungeonGrid.SIZE) {
            for (row in 0 until DungeonGrid.SIZE) {
                val worldX = START_X + column * (ROOM_SIZE shr 1)
                val worldZ = START_Z + row * (ROOM_SIZE shr 1)

                if (!WorldProbe.isChunkLoaded(worldX, worldZ)) {
                    allLoaded = false
                    continue
                }

                val known = grid[index(row, column)]
                if (known != null && (known as? RoomTile)?.data?.isUnknown() != true) continue

                val roof = WorldProbe.highestY(worldX, worldZ).takeUnless { it <= 0 } ?: continue
                grid[index(row, column)] = scanTile(worldX, worldZ, row, column, roof) ?: continue
            }
        }

        rooms.values.forEach(ScannedRoom::findRotation)
        if (allLoaded) complete = true
    }

    private fun scanTile(x: Int, z: Int, row: Int, column: Int, roof: Int): Tile? {
        val rowEven = row and 1 == 0
        val columnEven = column and 1 == 0

        return when {
            // Room centre.
            rowEven && columnEven -> {
                val data = RoomDataStore.byCore[WorldProbe.coreHash(x, z)] ?: return null
                RoomTile(x, z, data).also { attach(it, row, column) }
            }

            // Only ever the middle of a 2x2 room.
            !rowEven && !columnEven -> {
                val anchor = grid[index(row - 1, column - 1)] as? RoomTile ?: return null
                RoomTile(x, z, anchor.data).also {
                    it.isSeparator = true
                    attach(it, row, column)
                }
            }

            roof in DOOR_ROOF_HEIGHTS -> DoorTile(x, z)

            // Not a doorway, so the two rooms either side of this cell are one room.
            else -> {
                val neighbour = grid[if (rowEven) index(row, column - 1) else index(row - 1, column)]
                when {
                    neighbour !is RoomTile -> null
                    neighbour.data.type == RoomType.ENTRANCE -> DoorTile(x, z)
                    else -> RoomTile(x, z, neighbour.data).also {
                        it.isSeparator = true
                        attach(it, row, column)
                    }
                }
            }
        }
    }

    /**
     * Groups tiles by room name, which is how the layout stays one room per prefab. Two prefabs of
     * the same name never share a dungeon, so the name is a safe key.
     */
    private fun attach(tile: RoomTile, row: Int, column: Int) {
        val existing = rooms[tile.data.name]
        if (existing == null) {
            rooms[tile.data.name] = ScannedRoom(tile.data, tile, row, column).also { tile.room = it }
        } else {
            existing.addTile(row, column, tile)
            tile.room = existing
        }
    }
}
