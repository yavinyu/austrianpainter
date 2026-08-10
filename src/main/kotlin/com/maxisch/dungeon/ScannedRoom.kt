package com.maxisch.dungeon

import com.maxisch.dungeon.DungeonGrid.CLAY_CORNERS
import com.maxisch.dungeon.DungeonGrid.HALF_ROOM
import net.minecraft.core.BlockPos

/** All tiles of one prefab, plus the marker corner and rotation the paint scope needs. */
internal class ScannedRoom(val data: RoomData, first: RoomTile, row: Int, column: Int) {

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

    /**
     * The two corners of the whole prefab in world space, for "select this room" in the area
     * screen. [floor] is the bottom of the box - only the roof is known from the scan, so the
     * caller decides how far down to go.
     *
     * L rooms get no box on purpose: their bounding rectangle covers cells that belong to a
     * neighbouring room, and paint written there would be filed under this room's coordinates.
     */
    internal fun corners(floor: Int): Pair<BlockPos, BlockPos>? {
        if (data.shape == RoomShape.SL) return null
        return footprint(floor)
    }

    /**
     * The same box without the L-room refusal, for deciding which chunk sections to rebuild: marking
     * a few sections that belong to the neighbour costs a rebuild, not a wrong coordinate.
     */
    internal fun footprint(floor: Int): Pair<BlockPos, BlockPos>? {
        val solid = tiles.filterNot { it.isSeparator }
        if (solid.isEmpty()) return null
        val roof = highestBlock ?: WorldProbe.highestY(mainRoom.x, mainRoom.z).takeIf { it > 0 } ?: return null

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

        return BlockPos(minX - HALF_ROOM, floor, minZ - HALF_ROOM) to
            BlockPos(maxX + HALF_ROOM, roof, maxZ + HALF_ROOM)
    }

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
            ?: WorldProbe.highestY(mainRoom.x, mainRoom.z).takeIf { it > 0 }?.also { highestBlock = it }
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
                if (WorldProbe.blockAt(cursor) == WorldProbe.BLUE_TERRACOTTA) return orient(index, cursor)
            }
            return
        }

        for (tile in solid) {
            for (index in CLAY_CORNERS.indices) {
                val (offsetX, offsetZ) = CLAY_CORNERS[index]
                cursor.set(tile.x + offsetX, roof, tile.z + offsetZ)
                // An unloaded chunk here aborts the whole search rather than skipping this corner:
                // a later corner could match by accident and orient the room the wrong way round.
                if (!WorldProbe.isChunkLoaded(cursor.x, cursor.z)) return
                if (WorldProbe.blockAt(cursor) == WorldProbe.BLUE_TERRACOTTA) return orient(index, cursor)
            }
        }
    }

    private fun orient(cornerIndex: Int, pos: BlockPos) {
        clayPos = BlockPos(pos.x, 0, pos.z)
        rotation = cornerIndex * 90
    }
}
