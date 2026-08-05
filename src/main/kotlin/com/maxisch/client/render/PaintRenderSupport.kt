package com.maxisch.client.render

import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas
import net.fabricmc.fabric.api.client.renderer.v1.sprite.FabricTextureAtlas
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder
import net.minecraft.client.Minecraft

/** Caches the block atlas' [SpriteFinder], which is what lets us read a baked quad's source sprite. */
object PaintRenderSupport {

    @Volatile
    private var cached: SpriteFinder? = null

    fun blockSpriteFinder(): SpriteFinder? {
        cached?.let { return it }
        val found = runCatching {
            val atlas = Minecraft.getInstance().atlasManager.getAtlasOrThrow(QuadAtlas.BLOCK.id)
            (atlas as FabricTextureAtlas).spriteFinder()
        }.getOrNull()
        cached = found
        return found
    }

    fun invalidate() {
        cached = null
        RetexturePalette.invalidate()
    }
}
