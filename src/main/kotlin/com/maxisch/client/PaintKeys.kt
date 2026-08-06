package com.maxisch.client

import com.maxisch.client.gui.PaintScreen
import com.maxisch.paint.PaintStorage
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW

/**
 * Keybinds are deliberately raycast-only: painting never touches the block and never sends a
 * packet, so the mod stays invisible to any server.
 */
object ckPaintKeys {

    private val CATEGORY = KeyMapping.Category.register(Identifier.parse("austrianpainter:main"))

    private lateinit var openMenu: KeyMapping
    private lateinit var brushPaint: KeyMapping
    private lateinit var brushErase: KeyMapping

    fun register() {
        openMenu = bind("key.austrianpainter.open", GLFW.GLFW_KEY_P)
        brushPaint = bind("key.austrianpainter.brush_paint", GLFW.GLFW_KEY_G)
        brushErase = bind("key.austrianpainter.brush_erase", GLFW.GLFW_KEY_H)

        ClientTickEvents.END_CLIENT_TICK.register { client -> tick(client) }
    }

    /** True while the paint key is held; the scroll hook uses this to resize the brush. */
    @JvmStatic
    fun isBrushKeyDown(): Boolean = ::brushPaint.isInitialized && brushPaint.isDown

    private fun bind(translationKey: String, key: Int): KeyMapping =
        KeyMappingHelper.registerKeyMapping(
            KeyMapping(translationKey, InputConstants.Type.KEYSYM, key, CATEGORY),
        )

    private fun tick(client: Minecraft) {
        while (brushPaint.consumeClick()) stroke(client, paint = true)
        while (brushErase.consumeClick()) stroke(client, paint = false)
        while (openMenu.consumeClick()) {
            if (client.level != null) client.setScreenAndShow(PaintScreen())
        }
    }

    private fun stroke(client: Minecraft, paint: Boolean) {
        val level = client.level ?: return

        if (!PaintBrush.enabled) {
            tell(client, Component.translatable("austrianpainter.brush.disabled"))
            return
        }

        val center = PaintSelection.lookedAtPos() ?: run {
            tell(client, Component.translatable("austrianpainter.brush.miss"))
            return
        }

        // Air has no model to repaint, so leave it out of the stroke entirely.
        val positions = PaintBrush.cubeAround(center).filterNot { level.getBlockState(it).isAir }
        if (positions.isEmpty()) return

        if (paint) {
            val donor = PaintBrush.donor ?: run {
                tell(client, Component.translatable("austrianpainter.brush.no_donor"))
                return
            }
            val count = PaintStorage.paintPositions(positions, donor)
            tell(client, Component.translatable("austrianpainter.brush.painted", count, donor.name))
        } else {
            val count = PaintStorage.unpaintPositions(positions)
            tell(client, Component.translatable("austrianpainter.brush.erased", count))
        }
    }

    private fun tell(client: Minecraft, message: Component) {
        client.player?.sendSystemMessage(message)
    }
}
