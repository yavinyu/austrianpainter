package com.maxisch.dungeon.room

import net.minecraft.world.phys.Vec3
import kotlin.math.round

/**
 * The fixed geometry every Catacombs dungeon is laid out on.
 *
 * A dungeon is an 11x11 grid of 16-block cells over a fixed origin: even/even cells are room
 * centres, and the cells between them are either doorways or the inside of a room spanning more
 * than one tile.
 */
internal object DungeonGrid {

    const val START_X = -185
    const val START_Z = -185
    const val ROOM_SIZE = 32
    const val HALF_ROOM = 15

    /** Cells per side. The grid is stored flat, so this is also the row stride. */
    const val SIZE = 11

    /** Roof heights that mean "doorway", not "room". */
    val DOOR_ROOF_HEIGHTS = intArrayOf(73, 74, 81, 82)

    /** The four roof corners of a tile, in the rotation order the marker search relies on. */
    val CLAY_CORNERS = arrayOf(
        -HALF_ROOM to -HALF_ROOM,
        HALF_ROOM to -HALF_ROOM,
        HALF_ROOM to HALF_ROOM,
        -HALF_ROOM to HALF_ROOM,
    )

    /** Flat index into the grid array. */
    fun index(row: Int, column: Int): Int = row * SIZE + column

    /** Grid column/row for a world position, clamped to the dungeon's bounds. */
    fun cellAt(pos: Vec3): Pair<Int, Int> {
        val column = round((pos.x - START_X) / ROOM_SIZE).toInt() * 2
        val row = round((pos.z - START_Z) / ROOM_SIZE).toInt() * 2
        return column.coerceIn(0, SIZE - 1) to row.coerceIn(0, SIZE - 1)
    }
}
