package com.maxisch.client.render

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.client.resources.model.sprite.Material
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Block
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

        fun of(block: Block): RetexturePalette = CACHE.computeIfAbsent(block, ::build)

        private fun build(block: Block): RetexturePalette {
            val state = block.defaultBlockState()
            val model = Minecraft.getInstance().modelManager.blockStateModelSet.get(state)

            val parts = ArrayList<BlockStateModelPart>()
            model.collectParts(RandomSource.create(42L), parts)

            val perFace = arrayOfNulls<FaceMaterial>(Direction.entries.size)
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
                        if (perFace[face.ordinal] == null) perFace[face.ordinal] = entry
                        if (first == null) first = entry
                    }
                }
            }

            val particle = model.particleMaterial()
            val fallback = first ?: FaceMaterial(particle, -1)
            return RetexturePalette(perFace, fallback, particle, model.materialFlags(), first != null)
        }

        private val CULL_FACES: Array<Direction?> =
            arrayOf(*Direction.entries.toTypedArray(), null)
    }
}
