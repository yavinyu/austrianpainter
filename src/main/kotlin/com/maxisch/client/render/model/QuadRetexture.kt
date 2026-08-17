package com.maxisch.client.render.model

import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.block.BlockAndTintGetter
import net.minecraft.core.BlockPos
import net.minecraft.util.ARGB
import net.minecraft.world.level.block.state.BlockState

/**
 * Swaps one quad's sprite for a donor's, keeping every other property (position, shape) untouched.
 * Used by [PaintedBlockStateModel] for both the normal per-quad retexture path and the synthetic
 * full-cube path (barrier et al).
 */
object QuadRetexture {

    fun apply(
        quad: MutableQuadView,
        palette: RetexturePalette,
        finder: SpriteFinder,
        paintedState: BlockState,
        level: BlockAndTintGetter,
        pos: BlockPos,
    ) {
        // Undo the source sprite's atlas placement so UVs are back in 0..1 of its own texture,
        // then let materialBake place them into the borrowed sprite's atlas region.
        val source = finder.find(quad)
        val uSpan = source.u1 - source.u0
        val vSpan = source.v1 - source.v0
        if (uSpan != 0f && vSpan != 0f) {
            for (i in 0 until 4) {
                quad.uv(i, (quad.u(i) - source.u0) / uSpan, (quad.v(i) - source.v0) / vSpan)
            }
        }

        val face = palette.forFace(quad.nominalFace())
        quad.materialBake(face.material, MutableQuadView.BAKE_NORMALIZED)

        // Tint has to come from the block we borrowed from, not the block that is really here.
        // Bake it into vertex colour and clear the index so vanilla does not tint a second time
        // using the original state.
        if (face.tintIndex != -1) {
            val color = tintOf(paintedState, level, pos, face.tintIndex)
            quad.multiplyColor(ARGB.opaque(color))
        }
        quad.tintIndex(-1)
    }

    /** Also used directly by the synthetic-cube path (barrier et al.), which bakes its own material
     *  rather than going through [apply]. */
    fun tintOf(
        state: BlockState,
        level: BlockAndTintGetter,
        pos: BlockPos,
        tintIndex: Int,
    ): Int = runCatching {
        Minecraft.getInstance().blockColors.getTintSource(state, tintIndex)
            ?.colorInWorld(state, level, pos) ?: -1
    }.getOrDefault(-1)
}
