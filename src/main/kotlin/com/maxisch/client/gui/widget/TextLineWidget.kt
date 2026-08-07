package com.maxisch.client.gui.widget

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

/**
 * One line of left-aligned, coloured status text.
 *
 * Vanilla's `StringWidget` centres its text and offers no colour, and the paint UI leans on both -
 * grey for context, yellow for what is armed, red for a refusal.
 */
class TextLineWidget(
    x: Int,
    y: Int,
    width: Int,
    var color: Int,
) : AbstractWidget(x, y, width, HEIGHT, CommonComponents.EMPTY) {

    companion object {
        const val HEIGHT = 10
    }

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        graphics.text(Minecraft.getInstance().font, message, x, y, color)
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        output.add(NarratedElementType.TITLE, message)
    }
}
