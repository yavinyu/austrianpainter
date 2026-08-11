package com.maxisch.client.gui.widget

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.CommonComponents

/**
 * A decorative, non-interactive background: a translucent panel with a coloured accent bar
 * identifying the category of controls that sit on top of it. Must be [com.maxisch.client.gui.tab.ApTab.add]ed
 * before the widgets it groups - render order follows the tab's widget list order, so this has to
 * come first to sit underneath them rather than paint over them.
 */
class PanelWidget(
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    var accent: Int,
    private val accentOnTop: Boolean = true,
) : AbstractWidget(x, y, w, h, CommonComponents.EMPTY) {

    private companion object {
        const val BACKGROUND = 0x60000000
        const val OUTLINE = 0xFFFFFFFF.toInt()
        const val ACCENT_THICKNESS = 2
    }

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        graphics.fill(x, y, x + width, y + height, BACKGROUND)
        graphics.outline(x, y, width, height, OUTLINE)
        if (accentOnTop) {
            graphics.fill(x, y, x + width, y + ACCENT_THICKNESS, accent)
        } else {
            graphics.fill(x, y, x + ACCENT_THICKNESS, y + height, accent)
        }
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
}
