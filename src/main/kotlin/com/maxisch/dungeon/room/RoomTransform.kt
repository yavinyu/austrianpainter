package com.maxisch.dungeon.room

import net.minecraft.core.BlockPos

/**
 * Converts between world coordinates and coordinates relative to a room's marker corner.
 *
 * A room prefab is placed at a different grid cell and a different rotation every run, so anything
 * that has to survive a run - painted positions here - is stored relative and re-projected on
 * entry. Y never changes: Catacombs rooms always sit at the same height and rotation is about the
 * vertical axis.
 */
object RoomTransform {

    fun rotate(pos: BlockPos, degrees: Int): BlockPos = when ((degrees % 360 + 360) % 360) {
        90 -> BlockPos(pos.z, pos.y, -pos.x)
        180 -> BlockPos(-pos.x, pos.y, -pos.z)
        270 -> BlockPos(-pos.z, pos.y, pos.x)
        else -> BlockPos(pos.x, pos.y, pos.z)
    }

    /** Relative to world. */
    fun toWorld(pos: BlockPos, origin: BlockPos, rotation: Int): BlockPos =
        rotate(pos, rotation).offset(origin.x, 0, origin.z)

    /** World to relative. */
    fun toRelative(pos: BlockPos, origin: BlockPos, rotation: Int): BlockPos =
        rotate(pos.offset(-origin.x, 0, -origin.z), -rotation)
}
