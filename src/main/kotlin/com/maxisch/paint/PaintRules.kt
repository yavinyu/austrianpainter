package com.maxisch.paint

import com.maxisch.dungeon.RoomTransform
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.Block

/**
 * Every read and write of the live rule set.
 *
 * This is the single seam paint changes pass through, which is what lets an undo stack observe all
 * of them in one place rather than wrapping each caller.
 */
object PaintRules {

    internal val blocks: BlockPreset
        get() = PresetStores.blocks.active

    internal val types: TypePreset
        get() = PresetStores.types.active

    // ---------------------------------------------------------------- type rules

    fun typeRules(): Map<Block, Block> = types.map

    fun setTypeRule(source: Block, target: Block) {
        val prior = types.map.put(source, target)
        PaintHistory.recordTypeRule(source, prior, Component.translatable("austrianpainter.rule.type", source.name, target.name))
        applyTypeChange()
    }

    fun removeTypeRule(source: Block) {
        val prior = types.map.remove(source) ?: return
        PaintHistory.recordTypeRule(source, prior, Component.translatable("austrianpainter.rule.type", source.name, prior.name))
        applyTypeChange()
    }

    /** Undo path: puts the rule back exactly as it was, without recording the change. */
    internal fun restoreTypeRule(step: PaintHistory.TypeRule) {
        if (step.prior == null) types.map.remove(step.source) else types.map[step.source] = step.prior
        applyTypeChange()
    }

    /**
     * Type rules bypass the debounce and write straight away, so a crash cannot resurrect a rule
     * the player deleted - or lose one they just made.
     */
    private fun applyTypeChange() {
        PaintIndexBuilder.refresh()
        PresetStores.types.saveActive()
        ChunkRebuild.markAll()
    }

    // ---------------------------------------------------------------- positional rules

    /**
     * Where positional edits go: the active room's slice while one is in scope, the dimension's
     * otherwise. [toStorage] maps a world position into whatever space that slice is stored in.
     */
    private class Slice(
        val map: Long2ObjectOpenHashMap<Block>,
        val toStorage: (BlockPos) -> BlockPos,
    )

    private fun activeSlice(create: Boolean): Slice? {
        val room = PaintSession.scope
        if (room != null) {
            val store = if (room.isBoss) PresetStores.bosses else PresetStores.rooms
            val map = if (create) store.active.forKey(room.key) else store.active.positionsFor(room.key)
            return Slice(map ?: return null) { RoomTransform.toRelative(it, room.origin, room.rotation) }
        }

        val dimension = PaintSession.currentDimension() ?: return null
        val map = if (create) blocks.forDimension(dimension) else blocks.positionsIn(dimension)
        return Slice(map ?: return null) { it }
    }

    /** Donor block for every painted position in the active scope, grouped for the rule list. */
    fun positionsByDonor(): Map<Block, Int> {
        val slice = activeSlice(create = false) ?: return emptyMap()

        val counts = LinkedHashMap<Block, Int>()
        for (entry in slice.map.long2ObjectEntrySet()) {
            counts.merge(entry.value, 1, Int::plus)
        }
        return counts
    }

    fun paintPositions(positions: Collection<BlockPos>, target: Block): Int =
        paintPositions(positions) { target }

    /**
     * [donorFor] picks the donor per position, which is how a palette apply spreads a weighted mix
     * over the area. A lambda rather than a prepared map: an area can hold millions of positions.
     */
    fun paintPositions(positions: Collection<BlockPos>, donorFor: (BlockPos) -> Block): Int {
        if (positions.isEmpty()) return 0
        val slice = activeSlice(create = true) ?: return 0

        val step = PaintHistory.beginPositions(
            Component.translatable("austrianpainter.undo.label_paint"),
            positions.size,
        )

        // The donor is picked from the world position so a palette apply keeps its spread even
        // though the position is filed under the room's own coordinates.
        positions.forEach {
            val key = slice.toStorage(it).asLong()
            val prior = slice.map.put(key, donorFor(it))
            step?.record(key, prior)
        }
        PaintHistory.commit(step)

        PaintIndexBuilder.refresh()
        PaintSession.markDirty()
        ChunkRebuild.markRange(positions)
        return positions.size
    }

    fun unpaintPositions(positions: Collection<BlockPos>): Int {
        if (positions.isEmpty()) return 0
        val slice = activeSlice(create = false) ?: return 0

        val step = PaintHistory.beginPositions(
            Component.translatable("austrianpainter.undo.label_erase"),
            positions.size,
        )

        var removed = 0
        for (pos in positions) {
            val key = slice.toStorage(pos).asLong()
            val prior = slice.map.remove(key) ?: continue
            step?.record(key, prior)
            removed++
        }
        if (removed == 0) return 0

        PaintHistory.commit(step)
        PaintIndexBuilder.refresh()
        PaintSession.markDirty()
        ChunkRebuild.markRange(positions)
        return removed
    }

    /** Clears every position painted with [target] in the active scope. */
    fun removeDonor(target: Block): Int {
        val slice = activeSlice(create = false) ?: return 0
        val map = slice.map

        val doomed = LongOpenHashSet()
        for (entry in map.long2ObjectEntrySet()) {
            if (entry.value == target) doomed.add(entry.longKey)
        }
        if (doomed.isEmpty()) return 0

        val step = PaintHistory.beginPositions(
            Component.translatable("austrianpainter.undo.label_donor", target.name),
            doomed.size,
        )
        doomed.forEach { key ->
            step?.record(key, map.get(key))
            map.remove(key)
        }
        PaintHistory.commit(step)

        PaintIndexBuilder.refresh()
        PaintSession.markDirty()
        ChunkRebuild.markAll()
        return doomed.size
    }

    /** Clears everything painted in the active scope - the current room, or the dimension. */
    fun clearCurrentScope() {
        val map = activeSlice(create = false)?.map ?: return
        if (map.isEmpty()) return

        val step = PaintHistory.beginPositions(
            Component.translatable("austrianpainter.undo.label_clear"),
            map.size,
        )
        if (step != null) {
            for (entry in map.long2ObjectEntrySet()) step.record(entry.longKey, entry.value)
        }
        PaintHistory.commit(step)

        map.clear()
        PaintIndexBuilder.refresh()
        PaintSession.markDirty()
        ChunkRebuild.markAll()
    }

    /**
     * Undo path: writes a recorded step's prior state back. Rebuilds everything rather than the
     * touched range - the step's coordinates are in slice space, and projecting them back to world
     * space just to narrow a rebuild is not worth the room-rotation arithmetic.
     */
    internal fun restorePositions(step: PaintHistory.Positions): Int {
        val map = activeSlice(create = true)?.map ?: return 0

        step.wasUnpainted.forEach { key -> map.remove(key) }
        for (entry in step.wasPainted.long2ObjectEntrySet()) map.put(entry.longKey, entry.value)

        PaintIndexBuilder.refresh()
        PaintSession.markDirty()
        ChunkRebuild.markAll()
        return step.cost
    }
}
