package com.maxisch.client

import com.maxisch.paint.ApSettings
import com.maxisch.paint.PresetStores
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
        if (PaintBrush.enabled) {
            val donor = PaintBrush.donor
            val line = if (donor == null) {
                Component.translatable("austrianpainter.hud.no_donor")
            } else {
                Component.translatable("austrianpainter.hud.armed", donor.name, PaintBrush.radius)
            }
            graphics.text(client.font, line, 4, y, 0xFFFFFF55.toInt())
            y += 10
        }

        areaLine()?.let { graphics.text(client.font, it, 4, y, 0xFFFFAA55.toInt()) }
    }

    private fun areaLine(): Component? = when {
        PaintArea.complete -> Component.translatable(
            "austrianpainter.hud.area",
            PaintArea.volume(),
            sourceName(),
            PresetStores.palettes.activeName,
        )

        PaintArea.corner1 != null || PaintArea.corner2 != null ->
            Component.translatable("austrianpainter.hud.area_partial")

        else -> null
    }

    private fun sourceName(): Component = when {
        PaintArea.sourceAll -> Component.translatable("austrianpainter.area.everything")
        else -> PaintArea.source?.name ?: Component.translatable("austrianpainter.area.any_source")
    }
}
