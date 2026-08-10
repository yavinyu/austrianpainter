package com.maxisch.paint.session

import com.maxisch.paint.AreaSelector
import com.maxisch.paint.PaintFilter
import com.maxisch.paint.PaintIndex
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * Reads what is actually inside [PaintArea]. Blocks in unloaded chunks read as air, so the counts
 * only ever cover what the client can see; the area screen says as much.
 */
object AreaScan {

    /**
     * One block type in one paint state. [paintedAs] is null while nothing has painted it, which
     * the world alone cannot answer - a repainted block still reports its original type.
     */
    data class Entry(val block: Block, val paintedAs: Block?, val count: Int)

    /** What one walk of the box found: disjoint [entries] that add up to [total]. */
    data class Result(
        val entries: List<Entry>,
        val total: Int,
        val unpainted: Int,
    )

    /**
     * Non-air blocks inside the area, split by type *and* by what each one currently renders as, so
     * obsidian left alone and obsidian already turned to wool are counted apart. Most common first.
     */
    fun scan(level: Level): Result {
        val counts = HashMap<Block, HashMap<Block?, Int>>()
        var total = 0
        var unpainted = 0

        walk(level) { pos, state ->
            val paint = PaintIndex.paintAt(pos, state)
            counts.getOrPut(state.block) { HashMap() }.merge(paint, 1, Int::plus)
            total++
            if (paint == null) unpainted++
        }

        val entries = ArrayList<Entry>()
        for ((block, byPaint) in counts) {
            for ((paint, count) in byPaint) entries.add(Entry(block, paint, count))
        }
        entries.sortWith(
            compareByDescending<Entry> { it.count }
                .thenBy { it.block.name.string }
                .thenBy { it.paintedAs?.name?.string ?: "" },
        )
        return Result(entries, total, unpainted)
    }

    /**
     * Groups every matching position by selector in a single walk, so an area ruleset costs one
     * pass rather than one per rule.
     *
     * A position can satisfy several selectors at once - obsidian painted wool is also obsidian, is
     * also part of "everything". It is filed under the most specific one that asked for it, so no
     * position is painted twice by one apply:
     *
     * 1. the block type in exactly this paint state ([PaintFilter.PaintedAs] / [PaintFilter.Unpainted])
     * 2. the block type whatever its paint ([PaintFilter.AnyPaint])
     * 3. [AreaSelector.Unpainted] - anything nothing has painted
     * 4. [AreaSelector.Everything]
     */
    fun positionsFor(
        level: Level,
        selectors: Collection<AreaSelector>,
    ): Map<AreaSelector, List<BlockPos>> {
        if (selectors.isEmpty()) return emptyMap()

        // Nested maps rather than a Pair key: the lookup runs once per position, and an area can
        // hold millions of them.
        val paintedAs = HashMap<Block, HashMap<Block, AreaSelector>>()
        val typeUnpainted = HashMap<Block, AreaSelector>()
        val typeAny = HashMap<Block, AreaSelector>()
        var anyUnpainted: AreaSelector? = null
        var everything: AreaSelector? = null

        for (selector in selectors) {
            when (selector) {
                AreaSelector.Everything -> everything = selector
                AreaSelector.Unpainted -> anyUnpainted = selector
                is AreaSelector.Type -> when (val paint = selector.paint) {
                    PaintFilter.AnyPaint -> typeAny[selector.block] = selector
                    PaintFilter.Unpainted -> typeUnpainted[selector.block] = selector
                    is PaintFilter.PaintedAs ->
                        paintedAs.getOrPut(selector.block) { HashMap() }[paint.donor] = selector
                }
            }
        }

        // The index lookup is only worth doing when something actually asked about paint state.
        val needsPaint = paintedAs.isNotEmpty() || typeUnpainted.isNotEmpty() || anyUnpainted != null

        val hits = LinkedHashMap<AreaSelector, MutableList<BlockPos>>()
        walk(level) { pos, state ->
            val block = state.block
            val paint = if (needsPaint) PaintIndex.paintAt(pos, state) else null

            val matched = (if (paint != null) paintedAs[block]?.get(paint) else typeUnpainted[block])
                ?: typeAny[block]
                ?: (if (paint == null) anyUnpainted else null)
                ?: everything

            if (matched != null) hits.getOrPut(matched) { ArrayList() }.add(pos.immutable())
        }
        return hits
    }

    /**
     * One shared walk. The position handed to [visit] is a shared mutable cursor - copy it before
     * keeping it.
     */
    private inline fun walk(level: Level, visit: (BlockPos, BlockState) -> Unit) {
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
                    visit(cursor, state)
                }
            }
        }
    }
}
