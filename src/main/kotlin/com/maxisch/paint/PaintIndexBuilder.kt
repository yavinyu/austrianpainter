package com.maxisch.paint

import com.maxisch.dungeon.RoomTransform
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block

/**
 * Flattens the active presets into [PaintIndex].
 *
 * The index only ever sees absolute world positions, so the active room's relative positions are
 * projected here and nothing downstream has to know rooms exist.
 */
internal object PaintIndexBuilder {

    fun refresh() {
        val dimension = PaintSession.currentDimension()
        val absolute = dimension?.let { PaintRules.blocks.positionsIn(it) }
        val scope = PaintSession.scope
        val room = scope?.let { PaintRules.blocks.positionsInRoom(it.key) }?.takeUnless { it.isEmpty() }

        if (scope == null || room == null) {
            PaintIndex.rebuild(absolute, PaintRules.types.map)
            return
        }

        val merged = if (absolute == null) {
            Long2ObjectOpenHashMap<Block>(room.size)
        } else {
            Long2ObjectOpenHashMap(absolute)
        }
        for (entry in room.long2ObjectEntrySet()) {
            val world = RoomTransform.toWorld(BlockPos.of(entry.longKey), scope.origin, scope.rotation)
            merged.put(world.asLong(), entry.value)
        }
        PaintIndex.rebuild(merged, PaintRules.types.map)
    }
}
