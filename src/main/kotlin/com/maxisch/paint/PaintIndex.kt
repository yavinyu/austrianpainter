package com.maxisch.paint

import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * Flattened, read-only view of the active presets for the dimension the player is in, built for the
 * chunk-build hot path.
 *
 * Chunk building runs on worker threads while edits happen on the main thread, so mutation swaps a
 * whole new immutable [Snapshot] instead of editing maps in place. That costs a map copy per edit,
 * which is why edits are batched per brush stroke rather than per block.
 */
object PaintIndex {

    private class Snapshot(
        val byPos: Long2ObjectMap<Block>,
        val byBlock: Reference2ObjectMap<Block, Block>,
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

    /** [positions] is this dimension's slice of the active block preset; [types] is global. */
    fun rebuild(positions: Long2ObjectMap<Block>?, types: Map<Block, Block>) {
        if (positions.isNullOrEmptyMap() && types.isEmpty()) {
            snapshot = EMPTY
            return
        }

        val byPos = if (positions == null) Long2ObjectOpenHashMap() else Long2ObjectOpenHashMap(positions)
        val byBlock = Reference2ObjectOpenHashMap<Block, Block>(types.size)
        byBlock.putAll(types)
        snapshot = Snapshot(byPos, byBlock)
    }

    private fun Long2ObjectMap<Block>?.isNullOrEmptyMap(): Boolean = this == null || this.isEmpty()

    /**
     * Hot path. Returns null when [pos] is unpainted, which is the overwhelmingly common case and
     * costs a single volatile read when no rules exist at all.
     */
    @JvmStatic
    fun paintAt(pos: BlockPos?, state: BlockState): Block? {
        val snap = snapshot
        if (snap.empty) return null
        if (pos != null && snap.hasPositional) {
            val positional = snap.byPos.get(pos.asLong())
            if (positional != null) return positional
        }
        if (snap.hasTypes) return snap.byBlock[state.block]
        return null
    }

    /** Same lookup, gated on the global sound setting. */
    @JvmStatic
    fun soundPaintAt(pos: BlockPos?, state: BlockState): Block? {
        if (!ApSettings.paintSound) return null
        return paintAt(pos, state)
    }
}
