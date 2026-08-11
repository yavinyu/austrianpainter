package com.maxisch.client.gui.widget

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.CommonComponents

/**
 * A small filled-and-outlined preview of an ARGB colour, next to the hex box that edits it.
 *
 * There is no colour-picker widget anywhere in this codebase and building a full HSV picker is out
 * of scope for a settings tab - a hex box plus this live swatch is the minimal but real substitute.
 */
class ColorSwatchWidget(
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    var color: Int,
) : AbstractWidget(x, y, w, h, CommonComponents.EMPTY) {

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        graphics.fill(x, y, x + width, y + height, color)
        graphics.outline(x, y, width, height, 0xFFFFFFFF.toInt())
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
}
