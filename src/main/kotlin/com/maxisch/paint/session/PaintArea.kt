package com.maxisch.paint.session

import com.maxisch.paint.AreaSelector
import com.maxisch.paint.AreaTarget
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB

/**
 * Transient, client-only box selection: two corners plus the rules the area screen is building for
 * them. Kept apart from [PaintBrush] so replacing inside a box never disarms the brush.
 */
object PaintArea {

    /** Same ceiling the old region rules used; scanning much more than this stalls the client. */
    const val MAX_VOLUME = 2_000_000L

    var corner1: BlockPos? = null
    var corner2: BlockPos? = null

    /**
     * Rows the player has highlighted in the area list. Only an editing cursor: picking a donor
     * assigns it to everything in here, and nothing is painted off this set directly.
     */
    val selected: MutableSet<AreaSelector> = LinkedHashSet()

    /**
     * What a replace actually applies - the rule per selector, built up by assigning donors to the
     * selection. This is also exactly what gets saved as a ruleset.
     */
    val rules: MutableMap<AreaSelector, AreaTarget> = LinkedHashMap()

    /** The last applied rules, so the same look can be dropped onto the next box in one click. */
    var lastRules: Map<AreaSelector, AreaTarget> = emptyMap()

    /**
     * The palette-drawn positions of the last apply, keyed by the palette that drew them, so a
     * re-roll can redraw exactly that set from the same bag. Donor rules are left out: re-rolling a
     * fixed donor would only paint it again.
     */
    var lastPaletteApplied: Map<String, List<BlockPos>> = emptyMap()

    val complete: Boolean
        get() = corner1 != null && corner2 != null

    val hasRules: Boolean
        get() = rules.isNotEmpty()

    fun min(): BlockPos? {
        val a = corner1 ?: return null
        val b = corner2 ?: return null
        return BlockPos(minOf(a.x, b.x), clampY(minOf(a.y, b.y)), minOf(a.z, b.z))
    }

    fun max(): BlockPos? {
        val a = corner1 ?: return null
        val b = corner2 ?: return null
        return BlockPos(maxOf(a.x, b.x), clampY(maxOf(a.y, b.y)), maxOf(a.z, b.z))
    }

    fun volume(): Long {
        val min = min() ?: return 0L
        val max = max() ?: return 0L
        val width = (max.x - min.x + 1).toLong()
        val height = (max.y - min.y + 1).toLong()
        val depth = (max.z - min.z + 1).toLong()
        return width * height * depth
    }

    /** World-space box covering the whole selection, or a single cube while only one corner is set. */
    fun aabb(): AABB? {
        val min = min()
        val max = max()
        if (min != null && max != null) return AABB.encapsulatingFullBlocks(min, max)

        val lone = corner1 ?: corner2 ?: return null
        return AABB.encapsulatingFullBlocks(lone, lone)
    }

    fun positions(): Sequence<BlockPos> {
        val min = min() ?: return emptySequence()
        val max = max() ?: return emptySequence()

        return sequence {
            val cursor = BlockPos.MutableBlockPos()
            for (x in min.x..max.x) {
                for (y in min.y..max.y) {
                    for (z in min.z..max.z) {
                        yield(cursor.set(x, y, z).immutable())
                    }
                }
            }
        }
    }

    fun setCorner(first: Boolean, pos: BlockPos) {
        if (first) corner1 = pos.immutable() else corner2 = pos.immutable()
    }

    fun clearCorners() {
        corner1 = null
        corner2 = null
    }

    /**
     * Drops the box and the rules aimed at it. [lastRules] deliberately survives, so the same look
     * can still be dropped onto the next box - re-authoring it after every selection would be
     * tedious, and it is not part of the selection.
     */
    fun clearSelection() {
        clearCorners()
        selected.clear()
        rules.clear()
    }

    fun reset() {
        clearSelection()
        lastRules = emptyMap()
        lastPaletteApplied = emptyMap()
    }

    /** Coordinates typed into the screen can sit outside the world; the walk must not. */
    private fun clampY(y: Int): Int {
        val level = Minecraft.getInstance().level ?: return y
        return y.coerceIn(level.minY, level.maxY)
    }
}
