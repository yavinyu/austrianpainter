package com.maxisch.client

import com.maxisch.client.gui.PaintScreen
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW

/**
 * Keybinds are deliberately raycast-only: marking a corner never touches the block and never sends
 * a packet, so the mod stays invisible to any server.
 */
object PaintKeys {

    private val CATEGORY = KeyMapping.Category.register(Identifier.parse("austrianpainter:main"))

    private lateinit var openMenu: KeyMapping
    private lateinit var corner1: KeyMapping
    private lateinit var corner2: KeyMapping

    fun register() {
        openMenu = bind("key.austrianpainter.open", GLFW.GLFW_KEY_P)
        corner1 = bind("key.austrianpainter.corner1", GLFW.GLFW_KEY_LEFT_BRACKET)
        corner2 = bind("key.austrianpainter.corner2", GLFW.GLFW_KEY_RIGHT_BRACKET)

        ClientTickEvents.END_CLIENT_TICK.register { client -> tick(client) }
    }

    private fun bind(translationKey: String, key: Int): KeyMapping =
        KeyMappingHelper.registerKeyMapping(
            KeyMapping(translationKey, InputConstants.Type.KEYSYM, key, CATEGORY),
        )

    private fun tick(client: Minecraft) {
        while (corner1.consumeClick()) markCorner(client, first = true)
        while (corner2.consumeClick()) markCorner(client, first = false)
        while (openMenu.consumeClick()) {
            if (client.level != null) client.setScreenAndShow(PaintScreen())
        }
    }

    private fun markCorner(client: Minecraft, first: Boolean) {
        val pos = PaintSelection.lookedAtPos() ?: run {
            client.player?.sendSystemMessage(Component.translatable("austrianpainter.corner.miss"))
            return
        }
        if (first) PaintSelection.corner1 = pos else PaintSelection.corner2 = pos
        PaintSelection.mode = PaintSelection.Mode.REGION
        client.player?.sendSystemMessage(
            Component.translatable(
                if (first) "austrianpainter.corner.first" else "austrianpainter.corner.second",
                pos.x, pos.y, pos.z,
            ),
        )
    }
}
