package com.maxisch.client.render

import com.maxisch.paint.PaintIndex
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.block.BlockAndTintGetter
import net.minecraft.client.renderer.block.dispatch.BlockStateModel
import net.minecraft.client.resources.model.sprite.Material
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.ARGB
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.state.BlockState
import java.util.function.Predicate

/**
 * Wraps every baked block model. When a paint rule covers the block being drawn, the original
 * geometry is emitted unchanged and only each quad's sprite is swapped — so a painted stair is
 * still stair-shaped, a painted fence is still fence-shaped, whatever the source of the texture.
 */
class PaintedBlockStateModel(wrapped: BlockStateModel) : WrapperBlockStateModel(wrapped) {

    override fun emitQuads(
        emitter: QuadEmitter,
        level: BlockAndTintGetter,
        pos: BlockPos,
        state: BlockState,
        random: RandomSource,
        cullTest: Predicate<Direction?>,
    ) {
        val paint = PaintIndex.paintAt(pos, state)
        val finder = if (paint == null) null else PaintRenderSupport.blockSpriteFinder()
        if (paint == null || finder == null) {
            super.emitQuads(emitter, level, pos, state, random, cullTest)
            return
        }

        val palette = RetexturePalette.of(paint)
        val paintedState = paint.defaultBlockState()

        emitter.pushTransform { quad ->
            retexture(quad, palette, finder, paintedState, level, pos)
            true
        }
        try {
            super.emitQuads(emitter, level, pos, state, random, cullTest)
        } finally {
            emitter.popTransform()
        }
    }

    /**
     * Vanilla caches geometry across positions that share a key. A painted position must never
     * reuse an unpainted neighbour's cached geometry, so opt out of the cache entirely there.
     */
    override fun createGeometryKey(
        level: BlockAndTintGetter,
        pos: BlockPos,
        state: BlockState,
        random: RandomSource,
    ): Any? {
        if (PaintIndex.paintAt(pos, state) != null) return null
        return super.createGeometryKey(level, pos, state, random)
    }

    override fun particleMaterial(
        level: BlockAndTintGetter,
        pos: BlockPos,
        state: BlockState,
    ): Material.Baked {
        val paint = PaintIndex.paintAt(pos, state)
            ?: return super.particleMaterial(level, pos, state)
        return RetexturePalette.of(paint).particle
    }

    override fun materialFlags(
        level: BlockAndTintGetter,
        pos: BlockPos,
        state: BlockState,
        random: RandomSource,
    ): Int {
        val base = super.materialFlags(level, pos, state, random)
        val paint = PaintIndex.paintAt(pos, state) ?: return base
        return base or RetexturePalette.of(paint).materialFlags
    }

    private fun retexture(
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

    private fun tintOf(
        state: BlockState,
        level: BlockAndTintGetter,
        pos: BlockPos,
        tintIndex: Int,
    ): Int = runCatching {
        Minecraft.getInstance().blockColors.getTintSource(state, tintIndex)
            ?.colorInWorld(state, level, pos) ?: -1
    }.getOrDefault(-1)
}
