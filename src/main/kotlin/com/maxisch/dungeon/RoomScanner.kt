package com.maxisch.dungeon

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.phys.Vec3
import kotlin.math.round

/**
 * Rebuilds the Catacombs room layout from the blocks around the player.
 *
 * A dungeon is an 11x11 grid of 16-block cells laid over a fixed origin: even/even cells are room
 * centres, the cells between them are either doorways or the inside of a room that spans more than
 * one tile. A room is identified by hashing a column of blocks through its centre and looking that
 * hash up in [RoomDataStore], and it is oriented by finding the single blue terracotta block
 * Hypixel leaves at one corner of every room's roof.
 *
 * This is a trimmed port of NoammAddons' `DungeonScanner`/`UniqueRoom`: no map rendering, no
 * secrets, no score tracking - only what identifying and orienting a room needs.
 */
object RoomScanner {

    const val START_X = -185
    const val START_Z = -185
    const val ROOM_SIZE = 32
    const val HALF_ROOM = 15

    private const val RESCAN_DELAY_MS = 250L

    /** Roof heights that mean "doorway", not "room". */
    private val DOOR_ROOF_HEIGHTS = intArrayOf(73, 74, 81, 82)

    /** The marker Hypixel leaves on one roof corner of every room. */
    private val BLUE_TERRACOTTA = Blocks.DYED_TERRACOTTA.blue()

    private val CLAY_CORNERS = arrayOf(
        -HALF_ROOM to -HALF_ROOM,
        HALF_ROOM to -HALF_ROOM,
        HALF_ROOM to HALF_ROOM,
        -HALF_ROOM to HALF_ROOM,
    )

    sealed interface Tile {
        val x: Int
        val z: Int
    }

    class RoomTile(override val x: Int, override val z: Int, val data: RoomData) : Tile {
        var isSeparator = false
        var room: ScannedRoom? = null
    }

    class DoorTile(override val x: Int, override val z: Int) : Tile

    private class UnknownTile(override val x: Int, override val z: Int) : Tile

    /** All tiles of one prefab, plus the marker corner and rotation the paint scope needs. */
    class ScannedRoom(val data: RoomData, first: RoomTile, row: Int, column: Int) {

        val name: String = data.name
        val tiles = mutableListOf(first)

        var mainRoom: RoomTile = first
            private set

        var clayPos: BlockPos? = null
            private set

        var rotation: Int? = null
            private set

        var highestBlock: Int? = null

        private var topLeft = row to column

        val oriented: Boolean
            get() = clayPos != null && rotation != null

        fun addTile(row: Int, column: Int, tile: RoomTile) {
            tiles.removeIf { it.x == tile.x && it.z == tile.z }
            tiles.add(tile)

            if (row < topLeft.first || (row == topLeft.first && column < topLeft.second)) {
                topLeft = row to column
                mainRoom = tile
            }
        }

        /**
         * Looks for the blue terracotta marker on the roof. Rectangular rooms only ever carry it on
         * a corner of their bounding box; L rooms have no such corner, so every tile's corners get
         * probed instead.
         */
        fun findRotation() {
            if (oriented) return
            if (data.type == RoomType.FAIRY) {
                return orient(0, BlockPos(mainRoom.x - HALF_ROOM, 0, mainRoom.z - HALF_ROOM))
            }

            val solid = tiles.filterNot { it.isSeparator }
            if (solid.size < data.shape.tileCount) return

            val roof = highestBlock
                ?: highestY(mainRoom.x, mainRoom.z).takeIf { it > 0 }?.also { highestBlock = it }
                ?: return

            val cursor = BlockPos.MutableBlockPos()

            if (data.shape != RoomShape.SL) {
                var minX = Int.MAX_VALUE
                var maxX = Int.MIN_VALUE
                var minZ = Int.MAX_VALUE
                var maxZ = Int.MIN_VALUE
                for (tile in solid) {
                    if (tile.x < minX) minX = tile.x
                    if (tile.x > maxX) maxX = tile.x
                    if (tile.z < minZ) minZ = tile.z
                    if (tile.z > maxZ) maxZ = tile.z
                }

                val cornersX = intArrayOf(minX - HALF_ROOM, maxX + HALF_ROOM, maxX + HALF_ROOM, minX - HALF_ROOM)
                val cornersZ = intArrayOf(minZ - HALF_ROOM, minZ - HALF_ROOM, maxZ + HALF_ROOM, maxZ + HALF_ROOM)

                for (index in 0..3) {
                    cursor.set(cornersX[index], roof, cornersZ[index])
                    if (blockAt(cursor) == BLUE_TERRACOTTA) return orient(index, cursor)
                }
                return
            }

            for (tile in solid) {
                for (index in CLAY_CORNERS.indices) {
                    val (offsetX, offsetZ) = CLAY_CORNERS[index]
                    cursor.set(tile.x + offsetX, roof, tile.z + offsetZ)
                    if (!isChunkLoaded(cursor.x, cursor.z)) return
                    if (blockAt(cursor) == BLUE_TERRACOTTA) return orient(index, cursor)
                }
            }
        }

        private fun orient(cornerIndex: Int, pos: BlockPos) {
            clayPos = BlockPos(pos.x, 0, pos.z)
            rotation = cornerIndex * 90
        }
    }

    private val grid = arrayOfNulls<Tile>(121)
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

    fun roomAt(pos: Vec3): ScannedRoom? {
        val (column, row) = gridCell(pos)
        return (grid[row * 11 + column] as? RoomTile)?.room
    }

    /** Grid column/row for a world position, clamped to the dungeon's bounds. */
    fun gridCell(pos: Vec3): Pair<Int, Int> {
        val column = round((pos.x - START_X) / ROOM_SIZE).toInt() * 2
        val row = round((pos.z - START_Z) / ROOM_SIZE).toInt() * 2
        return column.coerceIn(0, 10) to row.coerceIn(0, 10)
    }

    private fun scan() {
        var allLoaded = true

        for (column in 0..10) {
            for (row in 0..10) {
                val worldX = START_X + column * (ROOM_SIZE shr 1)
                val worldZ = START_Z + row * (ROOM_SIZE shr 1)

                if (!isChunkLoaded(worldX, worldZ)) {
                    allLoaded = false
                    continue
                }

                val known = grid[row * 11 + column]
                if (known != null && (known as? RoomTile)?.data?.isUnknown() != true) continue

                val roof = highestY(worldX, worldZ).takeUnless { it <= 0 } ?: continue
                grid[row * 11 + column] = scanTile(worldX, worldZ, row, column, roof) ?: continue
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
                val data = RoomDataStore.byCore[core(x, z)] ?: return null
                RoomTile(x, z, data).also { attach(it, row, column) }
            }

            // Only ever the middle of a 2x2 room.
            !rowEven && !columnEven -> {
                val anchor = grid[(row - 1) * 11 + column - 1] as? RoomTile ?: return null
                RoomTile(x, z, anchor.data).also {
                    it.isSeparator = true
                    attach(it, row, column)
                }
            }

            roof in DOOR_ROOF_HEIGHTS -> DoorTile(x, z)

            // Not a doorway, so the two rooms either side of this cell are one room.
            else -> {
                val neighbour = grid[if (rowEven) row * 11 + column - 1 else (row - 1) * 11 + column]
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

    /**
     * Hash of the block column through a tile centre. Planks and chests are skipped because they
     * are placed per run, not per prefab.
     */
    private fun core(x: Int, z: Int): Int {
        val builder = StringBuilder(150)
        val cursor = BlockPos.MutableBlockPos(x, 0, z)
        val level = Minecraft.getInstance().level ?: return 0

        for (y in 140 downTo 12) {
            val id = LegacyBlockIds.idOf(level.getBlockState(cursor.setY(y)))
            if (id == 5 || id == 54 || id == 146) continue
            builder.append(id?.toString() ?: "null")
        }
        return builder.toString().hashCode()
    }

    /** Roof height at a column; gold blocks are ignored because they are run-specific decoration. */
    private fun highestY(x: Int, z: Int): Int {
        val level = Minecraft.getInstance().level ?: return 0
        val cursor = BlockPos.MutableBlockPos(x, 0, z)

        for (y in 256 downTo 0) {
            val state = level.getBlockState(cursor.setY(y))
            if (state.isAir || state.block == Blocks.GOLD_BLOCK) continue
            return y
        }
        return 0
    }

    private fun blockAt(pos: BlockPos) =
        Minecraft.getInstance().level?.getBlockState(pos)?.block ?: Blocks.AIR

    private fun isChunkLoaded(x: Int, z: Int): Boolean {
        val level: Level = Minecraft.getInstance().level ?: return false
        return level.getChunk(x shr 4, z shr 4, ChunkStatus.FULL, false) != null
    }
}
