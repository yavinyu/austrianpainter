package com.maxisch.paint

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block

/**
 * A single cosmetic paint rule. All rules are purely client-side: they only change how a block is
 * drawn, never what it is.
 */
sealed interface PaintRule {
    /** Block whose textures are borrowed. */
    val target: Block

    /** Whether the block's sounds should be borrowed too. */
    val paintSound: Boolean

    /** Every occurrence of [source] in this dimension. */
    data class OfType(
        val source: Block,
        override val target: Block,
        override val paintSound: Boolean,
    ) : PaintRule

    /** A single block position. */
    data class OfPos(
        val pos: BlockPos,
        override val target: Block,
        override val paintSound: Boolean,
    ) : PaintRule

    /** An inclusive box between [min] and [max]. */
    data class OfRegion(
        val min: BlockPos,
        val max: BlockPos,
        override val target: Block,
        override val paintSound: Boolean,
    ) : PaintRule {
        val volume: Long
            get() = (max.x - min.x + 1).toLong() *
                (max.y - min.y + 1).toLong() *
                (max.z - min.z + 1).toLong()
    }

    companion object {
        /** Regions above this many blocks are rejected, since every position is flattened into a map. */
        const val MAX_REGION_VOLUME: Long = 2_000_000L

        fun region(a: BlockPos, b: BlockPos, target: Block, paintSound: Boolean): OfRegion {
            val min = BlockPos(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z))
            val max = BlockPos(maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z))
            return OfRegion(min, max, target, paintSound)
        }
    }
}
