package com.maxisch.client.render

import com.maxisch.client.KeyHints
import com.maxisch.dungeon.DungeonLocation
import com.maxisch.paint.ApSettings
import com.maxisch.paint.PaintStorage
import com.maxisch.paint.PresetStores
import com.maxisch.paint.session.PaintArea
import com.maxisch.paint.session.PaintBrush
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/** Small readout so the armed donor and brush size are visible without opening the menu. */
object PaintHud : HudElement {

    private val ID = Identifier.parse("austrianpainter:brush_status")

    fun register() {
        HudElementRegistry.addLast(ID, this)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        if (!ApSettings.showHud) return

        val client = Minecraft.getInstance()
        // No need to check for a hidden HUD; vanilla skips the whole hud layer in that case.
        if (client.level == null) return

        var y = 4
        // Painting inside a dungeon room writes somewhere else entirely, so say so plainly.
        PaintStorage.scope?.let { scope ->
            val forced = DungeonLocation.forced
            graphics.text(
                client.font,
                Component.translatable(
                    if (forced) "austrianpainter.hud.room_forced" else "austrianpainter.hud.room",
                    scope.key,
                ),
                4,
                y,
                // Forced detection is a testing crutch; colour it differently so it is never
                // mistaken for the real thing.
                if (forced) 0xFFFFAA00.toInt() else 0xFF55FFFF.toInt(),
            )
            y += 10
        }

        if (PaintBrush.enabled) {
            val donor = PaintBrush.donor
            val line = if (donor == null) {
                Component.translatable("austrianpainter.hud.no_donor")
            } else {
                Component.translatable("austrianpainter.hud.armed", donor.name, PaintBrush.radius)
            }
            graphics.text(client.font, line, 4, y, 0xFFFFFF55.toInt())
            y += 10

            // Nothing else advertises the hold-and-scroll resize, so say it where the brush is.
            if (ApSettings.showHints) {
                graphics.text(client.font, KeyHints.resizeHint(), 4, y, 0xFF808080.toInt())
                y += 10
            }
        }

        areaLine()?.let {
            graphics.text(client.font, it, 4, y, 0xFFFFAA55.toInt())
            y += 10
        }

        // A highlight that quietly drew half the blocks would be worse than none, so say it refused.
        if (BlockHighlight.truncated) {
            graphics.text(
                client.font,
                Component.translatable("austrianpainter.overlay.capped"),
                4,
                y,
                0xFFFF5555.toInt(),
            )
        }
    }

    private fun areaLine(): Component? = when {
        PaintArea.complete -> Component.translatable(
            "austrianpainter.hud.area",
            PaintArea.volume(),
            PaintArea.rules.size,
            PresetStores.palettes.activeName,
        )

        PaintArea.corner1 != null || PaintArea.corner2 != null ->
            Component.translatable("austrianpainter.hud.area_partial")

        else -> null
    }
}
