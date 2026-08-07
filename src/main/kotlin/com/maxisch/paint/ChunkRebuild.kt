package com.maxisch.paint

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos

/**
 * Asks the renderer to rebuild the chunk sections a paint change can be seen in.
 *
 * Nothing here holds state - it is the one place that knows how a rule change turns into
 * `setSectionRangeDirty` calls, so the callers stay about rules rather than about rendering.
 */
internal object ChunkRebuild {

    /** The bounding box of [positions], padded by one section so neighbouring culling is redone. */
    fun markRange(positions: Collection<BlockPos>) {
        val level = Minecraft.getInstance().level ?: return
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var maxZ = Int.MIN_VALUE
        for (pos in positions) {
            if (pos.x < minX) minX = pos.x
            if (pos.y < minY) minY = pos.y
            if (pos.z < minZ) minZ = pos.z
            if (pos.x > maxX) maxX = pos.x
            if (pos.y > maxY) maxY = pos.y
            if (pos.z > maxZ) maxZ = pos.z
        }
        level.setSectionRangeDirty(
            SectionPos.blockToSectionCoord(minX) - 1,
            SectionPos.blockToSectionCoord(minY) - 1,
            SectionPos.blockToSectionCoord(minZ) - 1,
            SectionPos.blockToSectionCoord(maxX) + 1,
            SectionPos.blockToSectionCoord(maxY) + 1,
            SectionPos.blockToSectionCoord(maxZ) + 1,
        )
    }

    /** Type rules and preset swaps can hit anything, so rebuild the whole loaded view. */
    fun markAll() {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return
        val radius = mc.options.renderDistance().get() + 1
        val section = SectionPos.of(player.blockPosition())
        level.setSectionRangeDirty(
            section.x - radius, level.minSectionY, section.z - radius,
            section.x + radius, level.maxSectionY, section.z + radius,
        )
    }
}
