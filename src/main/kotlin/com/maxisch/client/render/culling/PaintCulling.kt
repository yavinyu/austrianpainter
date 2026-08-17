package com.maxisch.client.render.culling

import com.maxisch.paint.PaintIndex
import com.maxisch.paint.rule.DoorZones
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
 * The face is judged from whichever states are actually on screen: both painted, only one side
 * painted, or - the only case with nothing to correct - neither. Mixing in a side's real state once
 * it is actually painted would answer a question that is no longer true (a hypothetical "what if
 * only the other side were painted"), so exactly one combination is checked, never a vote across
 * several. Never culling more than vanilla matters as much as un-culling: answering purely from the
 * donor would make a slab painted as a full block swallow the face behind it. Drawing a face that
 * turns out to be hidden costs a few quads; not drawing one is a hole in the world.
 */
object PaintCulling {

    fun keepFace(
        level: BlockAndTintGetter,
        pos: BlockPos,
        state: BlockState,
        direction: Direction,
    ): Boolean {
        if (PaintIndex.isEmpty && !DoorZones.opacityActive()) return false

        val neighbourPos = pos.relative(direction)
        val neighbourState = level.getBlockState(neighbourPos)

        val selfPaint = PaintIndex.paintAt(pos, state)
        val neighbourPaint = PaintIndex.paintAt(neighbourPos, neighbourState)
        val selfOpacity = DoorZones.opacityAt(pos, state.block)
        val neighbourOpacity = DoorZones.opacityAt(neighbourPos, neighbourState.block)
        if (selfPaint == null && neighbourPaint == null && selfOpacity == null && neighbourOpacity == null) {
            return false
        }

        // Vanilla already draws it; nothing to correct.
        if (Block.shouldRenderFace(state, neighbourState, direction)) return false

        // A door rendering translucent has no donor to recompute shouldRenderFace against - its
        // real state (and shape) never changed, only its alpha - so there is nothing to feed
        // paintedFaceDecision. Vanilla culled this face because the door reads as fully opaque;
        // now that it is not, the face has to be drawn - but only where exactly one side is
        // opacity-affected. Two door blocks (or a door block and itself around a corner) sitting
        // against each other are opacity-affected on *both* sides; forcing the shared face on both
        // would double-draw the same coplanar quad and z-fight. That boundary is left to fall
        // through to paintedFaceDecision below instead, which - with no donor on either side -
        // reproduces vanilla's own answer: hide the seam, same as it always was.
        if ((selfOpacity != null) xor (neighbourOpacity != null)) {
            CullDiagnostics.record(true)
            return true
        }

        val keep = paintedFaceDecision(state, neighbourState, selfPaint, neighbourPaint, direction)

        CullDiagnostics.record(keep)
        return keep
    }

    /**
     * Symmetric to [keepFace], but removes a face vanilla's real states currently draw instead of
     * restoring one vanilla hides - for two blocks painted to look like they should fuse into one
     * continuous surface, mirroring vanilla's own same-block glass-stacking trick
     * (`HalfTransparentBlock.skipRendering`).
     *
     * Only ever fires when both real states are full *collision*-shape cubes. That is a purely
     * geometric guarantee, independent of opacity: a face wedged between two blocks that each
     * occupy their entire 1x1x1 volume can never expose anything else, no matter what donors are
     * involved - unlike occlusion (`canOcclude`/`getOcclusionShape`), which is false for vanilla
     * glass and would make this never fire for the exact case it targets.
     */
    fun suppressFace(
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

        // Vanilla already hides it; nothing to correct.
        if (!Block.shouldRenderFace(state, neighbourState, direction)) return false

        // Never remove real geometry's visibility unless it cannot possibly open a hole - both
        // real sides must fully occupy their cube, regardless of either one's texture.
        if (!state.isCollisionShapeFullBlock(level, pos) ||
            !neighbourState.isCollisionShapeFullBlock(level, neighbourPos)
        ) {
            return false
        }

        val suppress = !paintedFaceDecision(state, neighbourState, selfPaint, neighbourPaint, direction)

        CullDiagnostics.record(suppress)
        return suppress
    }

    /**
     * The one combination of states that actually matches what is on screen: each side uses its
     * donor if it has one, its real state if it does not. Never a vote across several
     * combinations - see the class doc for why that goes wrong once both sides are painted.
     */
    private fun paintedFaceDecision(
        state: BlockState,
        neighbourState: BlockState,
        selfPaint: Block?,
        neighbourPaint: Block?,
        direction: Direction,
    ): Boolean {
        val paintedSelf = selfPaint?.defaultBlockState() ?: state
        val paintedNeighbour = neighbourPaint?.defaultBlockState() ?: neighbourState
        return Block.shouldRenderFace(paintedSelf, paintedNeighbour, direction)
    }
}
