package com.maxisch.paint

import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/** Resolved paint for one block: which block to borrow from, and whether sounds follow. */
class Paint(val block: Block, val paintSound: Boolean)

/**
 * Flattened, read-only view of the active dimension's rules, built for the chunk-build hot path.
 *
 * Chunk building runs on worker threads while edits happen on the main thread, so mutation swaps a
 * whole new immutable [Snapshot] instead of editing maps in place.
 */
object PaintIndex {

    private class Snapshot(
        val byPos: Long2ObjectMap<Paint>,
        val byBlock: Reference2ObjectMap<Block, Paint>,
    ) {
        val empty: Boolean = byPos.isEmpty() && byBlock.isEmpty()
        val hasPositional: Boolean = !byPos.isEmpty()
        val hasTypes: Boolean = !byBlock.isEmpty()
    }

    private val EMPTY = Snapshot(Long2ObjectOpenHashMap(), Reference2ObjectOpenHashMap())

    @Volatile
    private var snapshot: Snapshot = EMPTY

    val isEmpty: Boolean
        get() = snapshot.empty

    fun clear() {
        snapshot = EMPTY
    }

    fun rebuild(rules: List<PaintRule>) {
        if (rules.isEmpty()) {
            snapshot = EMPTY
            return
        }
        val byPos = Long2ObjectOpenHashMap<Paint>()
        val byBlock = Reference2ObjectOpenHashMap<Block, Paint>()
        for (rule in rules) {
            val paint = Paint(rule.target, rule.paintSound)
            when (rule) {
                is PaintRule.OfType -> byBlock[rule.source] = paint
                is PaintRule.OfPos -> byPos.put(rule.pos.asLong(), paint)
                is PaintRule.OfRegion -> {
                    if (rule.volume > PaintRule.MAX_REGION_VOLUME) continue
                    for (pos in BlockPos.betweenClosed(rule.min, rule.max)) {
                        byPos.put(pos.asLong(), paint)
                    }
                }
            }
        }
        snapshot = Snapshot(byPos, byBlock)
    }

    /**
     * Hot path. Returns null when [pos] is unpainted, which is the overwhelmingly common case and
     * costs a single volatile read when no rules exist at all.
     */
    @JvmStatic
    fun paintAt(pos: BlockPos?, state: BlockState): Paint? {
        val snap = snapshot
        if (snap.empty) return null
        if (pos != null && snap.hasPositional) {
            val positional = snap.byPos.get(pos.asLong())
            if (positional != null) return positional
        }
        if (snap.hasTypes) return snap.byBlock[state.block]
        return null
    }

    /** Same lookup, restricted to rules that opted into sound painting. */
    @JvmStatic
    fun soundPaintAt(pos: BlockPos?, state: BlockState): Block? {
        val paint = paintAt(pos, state) ?: return null
        return if (paint.paintSound) paint.block else null
    }
}
