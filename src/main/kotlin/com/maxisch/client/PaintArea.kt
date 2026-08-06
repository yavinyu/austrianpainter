package com.maxisch.client

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.AABB

/**
 * Transient, client-only box selection: two corners plus the source/donor pair the area screen is
 * working with. Kept apart from [PaintBrush] so replacing inside a box never disarms the brush.
 */
object PaintArea {

    /** Same ceiling the old region rules used; scanning much more than this stalls the client. */
    const val MAX_VOLUME = 2_000_000L

    var corner1: BlockPos? = null
    var corner2: BlockPos? = null

    /** Block type inside the area that a replace will target; ignored while [sourceAll] is set. */
    var source: Block? = null

    /** Target every non-air block in the box rather than one type. */
    var sourceAll: Boolean = false

    /** What the last apply touched, so a re-roll can redraw exactly that set. */
    var lastApplied: List<BlockPos> = emptyList()

    val complete: Boolean
        get() = corner1 != null && corner2 != null

    val hasSource: Boolean
        get() = sourceAll || source != null

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

    fun reset() {
        clearCorners()
        source = null
        sourceAll = false
        lastApplied = emptyList()
    }

    /** Coordinates typed into the screen can sit outside the world; the walk must not. */
    private fun clampY(y: Int): Int {
        val level = Minecraft.getInstance().level ?: return y
        return y.coerceIn(level.minY, level.maxY)
    }
}
