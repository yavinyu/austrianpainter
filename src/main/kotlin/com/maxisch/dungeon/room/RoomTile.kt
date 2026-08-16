package com.maxisch.dungeon.room

/** One cell of the dungeon grid, once the scanner has worked out what it is. */
internal sealed interface Tile {
    val x: Int
    val z: Int
}

/**
 * A cell that belongs to a room. [isSeparator] marks a cell the scanner inferred from a neighbour
 * rather than identified on its own, which is how rooms wider than one tile are stitched together.
 */
internal class RoomTile(override val x: Int, override val z: Int, val data: RoomData) : Tile {
    var isSeparator = false
    var room: ScannedRoom? = null
}

/** A cell between two rooms. */
internal class DoorTile(override val x: Int, override val z: Int) : Tile
