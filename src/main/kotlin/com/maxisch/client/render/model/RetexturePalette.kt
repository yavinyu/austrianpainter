package com.maxisch.client.render.model

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.client.resources.model.geometry.BakedQuad
import net.minecraft.client.resources.model.sprite.Material
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import java.util.concurrent.ConcurrentHashMap

/**
 * The textures a painted block borrows, resolved once per target block and reused for every quad.
 *
 * Faces are kept apart so anisotropic targets (grass block, logs) still look right when their
 * textures are pulled onto some other shape.
 */
class RetexturePalette private constructor(
    private val perFace: Array<FaceMaterial?>,
    val fallback: FaceMaterial,
    val particle: Material.Baked,
    val materialFlags: Int,
    /**
     * Whether the donor's model actually produced any quads to borrow textures from. Blocks that
     * render nothing in the world - barrier, air, structure void, light - have none, and their
     * particle sprite is not in the block atlas, so borrowing from them lands on an arbitrary
     * region of it. Such a donor is refused rather than drawn wrong.
     */
    val usable: Boolean,
) {

    class FaceMaterial(val material: Material.Baked, val tintIndex: Int)

    fun forFace(face: Direction?): FaceMaterial =
        if (face == null) fallback else perFace[face.ordinal] ?: fallback

    companion object {
        private val CACHE = ConcurrentHashMap<Block, RetexturePalette>()

        /** Model bakery output changed under us; drop everything derived from it. */
        fun invalidate() = CACHE.clear()

        @JvmStatic
        fun of(block: Block): RetexturePalette = CACHE.computeIfAbsent(block, ::build)

        private fun build(block: Block): RetexturePalette {
            val state = block.defaultBlockState()
            val model = Minecraft.getInstance().modelManager.blockStateModelSet.get(state)

            val parts = ArrayList<BlockStateModelPart>()
            model.collectParts(RandomSource.create(42L), parts)

            val perFace = arrayOfNulls<FaceMaterial>(Direction.entries.size)
            val perFaceArea = FloatArray(Direction.entries.size)
            var first: FaceMaterial? = null

            for (part in parts) {
                // null is the "not culled by any face" bucket, same iteration vanilla uses.
                for (cullFace in CULL_FACES) {
                    for (quad in part.getQuads(cullFace)) {
                        val info = quad.materialInfo()
                        val face = cullFace ?: quad.direction()
                        val entry = FaceMaterial(
                            Material.Baked(info.sprite(), info.layer() == ChunkSectionLayer.TRANSLUCENT),
                            info.tintIndex(),
                        )
                        if (first == null) first = entry
                        // A donor shaped from several elements (composter's walls, etc) can have
                        // more than one quad resolve to the same face; keep whichever covers the
                        // most of it, not just the first one seen.
                        val area = faceArea(quad, face)
                        if (perFace[face.ordinal] == null || area > perFaceArea[face.ordinal]) {
                            perFace[face.ordinal] = entry
                            perFaceArea[face.ordinal] = area
                        }
                    }
                }
            }

            val particle = model.particleMaterial()

            // Blocks rendered by a BlockEntityRenderer (shulker box, chest, bed, skull, banner,
            // ...) have no baked quads to borrow from, unlike barrier/light/structure_void whose
            // particle sprite is a placeholder outside the block atlas - EntityBlock is what
            // distinguishes them. Their particle sprite (shown when the block breaks) is a real
            // atlas texture and the closest single-texture stand-in available, so use it for
            // every face instead of painting the target fully invisible.
            if (first == null && block is EntityBlock) {
                val fallbackEntry = FaceMaterial(particle, -1)
                return RetexturePalette(
                    arrayOfNulls(Direction.entries.size),
                    fallbackEntry,
                    particle,
                    model.materialFlags(),
                    true,
                )
            }

            val fallback = first ?: FaceMaterial(particle, -1)
            return RetexturePalette(perFace, fallback, particle, model.materialFlags(), first != null)
        }

        /** 2D footprint of [quad] projected onto the plane perpendicular to [face]'s axis - used
         *  to pick the quad that covers the most of a face when a donor's shape isn't a plain
         *  cube. Unit-agnostic: only ever compared against other quads' areas from the same
         *  donor, never against an absolute "full block" constant. */
        private fun faceArea(quad: BakedQuad, face: Direction): Float {
            var aMin = Float.MAX_VALUE
            var aMax = -Float.MAX_VALUE
            var bMin = Float.MAX_VALUE
            var bMax = -Float.MAX_VALUE
            for (i in 0 until 4) {
                val pos = quad.position(i)
                val (a, b) = when (face.axis) {
                    Direction.Axis.X -> pos.y() to pos.z()
                    Direction.Axis.Y -> pos.x() to pos.z()
                    Direction.Axis.Z -> pos.x() to pos.y()
                }
                if (a < aMin) aMin = a
                if (a > aMax) aMax = a
                if (b < bMin) bMin = b
                if (b > bMax) bMax = b
            }
            return (aMax - aMin) * (bMax - bMin)
        }

        private val CULL_FACES: Array<Direction?> =
            arrayOf(*Direction.entries.toTypedArray(), null)
    }
}
