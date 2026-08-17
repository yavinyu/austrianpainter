package com.maxisch.client.render.model

import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas
import net.fabricmc.fabric.api.client.renderer.v1.sprite.FabricTextureAtlas
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.model.sprite.Material
import net.minecraft.resources.Identifier

/** Caches the block atlas' [SpriteFinder], which is what lets us read a baked quad's source sprite. */
object PaintRenderSupport {

    @Volatile
    private var cached: SpriteFinder? = null

    /** `austrianpainter:block/flat_tint` baked as a [Material.Baked] - a plain opaque-white sprite
     *  this mod ships itself, never touched by the active resource-pack stack. Used by
     *  [ReplaceFluid] as a multiply-neutral stand-in for a fluid's pack-resolved sprite, so its
     *  "flat" tint mode multiplies against white instead of whatever the pack drew. */
    @Volatile
    private var flatTintCached: Material.Baked? = null

    private val FLAT_TINT_SPRITE = Identifier.fromNamespaceAndPath("austrianpainter", "block/flat_tint")

    fun blockSpriteFinder(): SpriteFinder? {
        cached?.let { return it }
        val found = runCatching {
            val atlas = Minecraft.getInstance().atlasManager.getAtlasOrThrow(QuadAtlas.BLOCK.id)
            (atlas as FabricTextureAtlas).spriteFinder()
        }.getOrNull()
        cached = found
        return found
    }

    fun flatTintMaterial(): Material.Baked? {
        flatTintCached?.let { return it }
        val found = runCatching {
            val atlas = Minecraft.getInstance().atlasManager.getAtlasOrThrow(QuadAtlas.BLOCK.id)
            Material.Baked(atlas.getSprite(FLAT_TINT_SPRITE), true)
        }.getOrNull()
        flatTintCached = found
        return found
    }

    fun invalidate() {
        cached = null
        flatTintCached = null
        RetexturePalette.invalidate()
    }
}
