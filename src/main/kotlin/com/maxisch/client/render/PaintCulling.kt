package com.maxisch.client.render

import com.maxisch.paint.PaintIndex
import net.minecraft.client.renderer.block.BlockAndTintGetter
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * Whether a face vanilla wants to hide has to be drawn anyway because paint changed what is on one
 * side of it.
 *
 * Face culling is decided from the blocks that are really there, and paint only swaps textures, so
 * the two disagree. A stained glass block repainted as clear glass is suddenly see-through, but the
 * faces around it were hidden back when it was opaque - leaving a hole to look into.
 *
 * The face is kept whenever the real blocks, the painted ones, or any mix of the two want it. Never
 * culling more than vanilla matters as much as un-culling: answering purely from the donor would
 * make a slab painted as a full block swallow the face behind it. Drawing a face that turns out to
 * be hidden costs a few quads; not drawing one is a hole in the world.
 */
object PaintCulling {

    fun keepFace(
        level: BlockAndTintGetter,
        pos: BlockPos,
        state: BlockState,
        direction: Direction,
    ): Boolean {
        if (PaintIndex.isEmpty) return false

        val neighbourPos = pos.relative(direction)
        val neighbourState = level.getBlockState(neighbourPos)

        val selfPaint = PaintIndex.paintAt(pos, state)
        val neighbourPaint = PaintIndex.paintAt(neighbourPos, neighbourState)
        if (selfPaint == null && neighbourPaint == null) return false

        // Vanilla already draws it; nothing to correct.
        if (Block.shouldRenderFace(state, neighbourState, direction)) return false

        val paintedSelf = selfPaint?.defaultBlockState() ?: state
        val paintedNeighbour = neighbourPaint?.defaultBlockState() ?: neighbourState

        val keep = Block.shouldRenderFace(paintedSelf, neighbourState, direction) ||
            Block.shouldRenderFace(state, paintedNeighbour, direction) ||
            Block.shouldRenderFace(paintedSelf, paintedNeighbour, direction)

        CullDiagnostics.record(keep)
        return keep
    }
}
