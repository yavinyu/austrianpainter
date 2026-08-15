package com.maxisch.client.gui.widget

import com.maxisch.client.gui.Theme
import com.maxisch.client.gui.nvgSurface
import com.maxisch.client.render.render2d.NVGUtils
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
        const val ACCENT_THICKNESS = 2
    }

    init {
        // A drawing-only widget must be invisible to hit-testing: getChildAt returns the first child
        // whose isMouseOver is true, and isMouseOver gates on `active`. A panel is added before the
        // controls it groups - it has to be, to draw underneath them - so while it stays active it
        // wins that test for its whole band and swallows every click meant for them.
        active = false
    }

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        nvgSurface(
            graphics, x, y, width, height,
            nvg = {
                val left = x.toFloat()
                val top = y.toFloat()
                val w = width.toFloat()
                val h = height.toFloat()

                NVGUtils.drawRect(left, top, w, h, Theme.RADIUS, Theme.c(Theme.PANEL))

                // The accent is the panel's own rounded shape clipped to a strip, not a rect of its
                // own: a plain rect would square off the two corners the strip shares with the panel.
                if (accentOnTop) {
                    NVGUtils.pushScissor(left, top, w, ACCENT_THICKNESS.toFloat())
                } else {
                    NVGUtils.pushScissor(left, top, ACCENT_THICKNESS.toFloat(), h)
                }
                NVGUtils.drawRect(left, top, w, h, Theme.RADIUS, Theme.c(accent))
                NVGUtils.popScissor()

                NVGUtils.drawOutlineRect(
                    left + Theme.OUTLINE_INSET,
                    top + Theme.OUTLINE_INSET,
                    w - Theme.OUTLINE_THICKNESS,
                    h - Theme.OUTLINE_THICKNESS,
                    Theme.RADIUS,
                    Theme.OUTLINE_THICKNESS,
                    Theme.c(Theme.OUTLINE),
                )
            },
            vanilla = {
                graphics.fill(x, y, x + width, y + height, Theme.PANEL)
                graphics.outline(x, y, width, height, Theme.OUTLINE)
                if (accentOnTop) {
                    graphics.fill(x, y, x + width, y + ACCENT_THICKNESS, accent)
                } else {
                    graphics.fill(x, y, x + ACCENT_THICKNESS, y + height, accent)
                }
            },
        )
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
}
