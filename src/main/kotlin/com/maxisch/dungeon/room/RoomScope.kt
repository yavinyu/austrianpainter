package com.maxisch.dungeon.room

import net.minecraft.core.BlockPos

/**
 * Which room's paint is currently live.
 *
 * A scope is only handed out once the room is oriented, so paint can never bind to a room that is
 * still half scanned and end up stored against the wrong corner.
 */
data class RoomScope(val key: String, val origin: BlockPos, val rotation: Int, val isBoss: Boolean)
