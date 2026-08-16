package com.maxisch.client.gui.widget

import com.maxisch.client.gui.Theme
import com.maxisch.client.gui.nvgSurface
import com.maxisch.client.render.render2d.NVGUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
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

    /** Set to open a picker; while null the swatch stays decorative and takes no clicks. */
    var onPress: (() -> Unit)? = null
        set(value) {
            field = value
            // Only a swatch that does something may take a click. A decorative one must stay out of
            // hit-testing entirely - see PanelWidget for what happens when it does not.
            active = value != null
        }

    init {
        active = false
    }

    override fun onClick(event: MouseButtonEvent, doubled: Boolean) {
        onPress?.invoke()
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
                NVGUtils.drawRect(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), Theme.RADIUS, Theme.c(color))
                NVGUtils.drawOutlineRect(
                    x + Theme.OUTLINE_INSET,
                    y + Theme.OUTLINE_INSET,
                    width - Theme.OUTLINE_THICKNESS,
                    height - Theme.OUTLINE_THICKNESS,
                    Theme.RADIUS,
                    Theme.OUTLINE_THICKNESS,
                    Theme.c(Theme.OUTLINE),
                )
            },
            vanilla = {
                graphics.fill(x, y, x + width, y + height, color)
                graphics.outline(x, y, width, height, Theme.OUTLINE)
            },
        )
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
}
