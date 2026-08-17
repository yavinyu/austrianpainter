package com.maxisch.client.render.model

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
import com.maxisch.client.render.culling.PaintCulling

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
        val donorPalette = if (paint == null || finder == null) null else RetexturePalette.of(paint)

        // A donor that draws nothing itself - barrier, structure void, light - has no textures to
        // lend, so the block is painted invisible instead. The block is still really there, so it
        // keeps its collision and its selection outline, exactly like a barrier.
        if (donorPalette != null && !donorPalette.usable) return

        val palette = donorPalette

        // Culling is decided from the real blocks, which paint contradicts: a stained glass block
        // repainted as clear glass stops hiding the faces around it. That applies to every block
        // next to paint, not just painted ones, so this runs whenever any paint exists at all.
        val fixCulling = !PaintIndex.isEmpty

        if (palette == null && !fixCulling) {
            super.emitQuads(emitter, level, pos, state, random, cullTest)
            return
        }

        // The test has to be corrected as well as the quads: a face the test rejects is dropped
        // before ever reaching the transform below.
        val paintAwareCullTest = if (!fixCulling) {
            cullTest
        } else {
            Predicate<Direction?> { direction ->
                if (direction == null) {
                    cullTest.test(direction)
                } else if (cullTest.test(direction)) {
                    !PaintCulling.keepFace(level, pos, state, direction)
                } else {
                    PaintCulling.suppressFace(level, pos, state, direction)
                }
            }
        }

        // A target that bakes no geometry of its own - barrier chief among them - has no quads
        // for retexture() to swap sprites onto, so a full cube is synthesized from the donor's
        // palette instead. The block is still really there underneath, so a painted barrier keeps
        // barrier's collision and outline while looking like the donor.
        if (palette != null && !RetexturePalette.of(state.block).usable) {
            for (direction in Direction.entries) {
                if (paintAwareCullTest.test(direction)) continue
                val face = palette.forFace(direction)
                emitter.square(direction, 0f, 0f, 1f, 1f, 0f)
                emitter.materialBake(face.material, MutableQuadView.BAKE_LOCK_UV or MutableQuadView.BAKE_NORMALIZED)
                if (face.tintIndex != -1) {
                    val color = tintOf(paint!!.defaultBlockState(), level, pos, face.tintIndex)
                    emitter.multiplyColor(ARGB.opaque(color))
                }
                emitter.tintIndex(-1)
                emitter.emit()
            }
            return
        }

        emitter.pushTransform { quad ->
            if (fixCulling) {
                val culled = quad.cullFace()
                if (culled != null) {
                    if (PaintCulling.keepFace(level, pos, state, culled)) {
                        quad.cullFace(null)
                    } else if (PaintCulling.suppressFace(level, pos, state, culled)) {
                        return@pushTransform false
                    }
                }
            }
            // finder and paint are non-null whenever palette is - see donorPalette above - Kotlin
            // just can't see it through the separate locals.
            if (palette != null) retexture(quad, palette, finder!!, paint!!.defaultBlockState(), level, pos)
            true
        }
        try {
            super.emitQuads(emitter, level, pos, state, random, paintAwareCullTest)
        } finally {
            emitter.popTransform()
        }
    }

    /**
     * Vanilla caches geometry across positions that share a key. A painted position must never
     * reuse an unpainted neighbour's cached geometry, so opt out of the cache entirely there - and
     * equally for a block merely next to paint, whose culling now depends on that paint.
     */
    override fun createGeometryKey(
        level: BlockAndTintGetter,
        pos: BlockPos,
        state: BlockState,
        random: RandomSource,
    ): Any? {
        if (!PaintIndex.isEmpty) {
            if (PaintIndex.paintAt(pos, state) != null) return null

            val cursor = BlockPos.MutableBlockPos()
            for (direction in Direction.entries) {
                cursor.setWithOffset(pos, direction)
                if (PaintIndex.paintAt(cursor, level.getBlockState(cursor)) != null) return null
            }
        }
        return super.createGeometryKey(level, pos, state, random)
    }

    override fun particleMaterial(
        level: BlockAndTintGetter,
        pos: BlockPos,
        state: BlockState,
    ): Material.Baked {
        val paint = PaintIndex.paintAt(pos, state)
            ?: return super.particleMaterial(level, pos, state)
        val palette = RetexturePalette.of(paint)
        if (!palette.usable) return super.particleMaterial(level, pos, state)
        return palette.particle
    }

    override fun materialFlags(
        level: BlockAndTintGetter,
        pos: BlockPos,
        state: BlockState,
        random: RandomSource,
    ): Int {
        val base = super.materialFlags(level, pos, state, random)
        val paint = PaintIndex.paintAt(pos, state) ?: return base
        val palette = RetexturePalette.of(paint)
        return if (palette.usable) base or palette.materialFlags else base
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
