package com.maxisch.client

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block

/**
 * Reads what is actually inside [PaintArea]. Blocks in unloaded chunks read as air, so the counts
 * only ever cover what the client can see; the area screen says as much.
 */
object AreaScan {

    /** Non-air block types inside the area with their counts, most common first. */
    fun histogram(level: Level): LinkedHashMap<Block, Int> {
        val counts = HashMap<Block, Int>()
        walk(level) { _, block -> counts.merge(block, 1, Int::plus) }

        val sorted = LinkedHashMap<Block, Int>(counts.size)
        counts.entries
            .sortedWith(compareByDescending<Map.Entry<Block, Int>> { it.value }.thenBy { it.key.name.string })
            .forEach { sorted[it.key] = it.value }
        return sorted
    }

    /** Every position inside the area holding [source]. */
    fun positionsOf(level: Level, source: Block): List<BlockPos> {
        val hits = ArrayList<BlockPos>()
        walk(level) { pos, block -> if (block == source) hits.add(pos.immutable()) }
        return hits
    }

    /**
     * One shared walk. The position handed to [visit] is a shared mutable cursor - copy it before
     * keeping it.
     */
    private inline fun walk(level: Level, visit: (BlockPos, Block) -> Unit) {
        val min = PaintArea.min() ?: return
        val max = PaintArea.max() ?: return
        if (PaintArea.volume() > PaintArea.MAX_VOLUME) return

        val cursor = BlockPos.MutableBlockPos()
        for (x in min.x..max.x) {
            for (y in min.y..max.y) {
                for (z in min.z..max.z) {
                    cursor.set(x, y, z)
                    val state = level.getBlockState(cursor)
                    if (state.isAir) continue
                    visit(cursor, state.block)
                }
            }
        }
    }
}
