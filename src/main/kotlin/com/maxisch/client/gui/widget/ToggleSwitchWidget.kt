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
 * A hand-rolled on/off switch: the track fills with [accent] when [checked] and a small knob slides
 * to the side matching the state. Replaces the on/off-label-swap [net.minecraft.client.gui.components.Button]
 * pattern used everywhere else in this codebase for booleans - state reads at a glance from track
 * colour and knob position instead of requiring the label text to be read.
 */
class ToggleSwitchWidget(
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    var checked: Boolean,
    var accent: Int,
    var onToggle: () -> Unit,
) : AbstractWidget(x, y, w, h, CommonComponents.EMPTY) {

    private companion object {
        const val KNOB_INSET = 2
    }

    /** Flips no local state - the caller owns the real value and drives [checked] back via refresh. */
    override fun onClick(event: MouseButtonEvent, doubled: Boolean) {
        onToggle()
    }

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val hovered = active && mouseX in x..(x + width) && mouseY in y..(y + height)
        val track = when {
            !active -> Theme.SWITCH_TRACK_DISABLED
            checked -> accent
            else -> Theme.SWITCH_TRACK_OFF
        }
        val outline = if (active) Theme.OUTLINE else Theme.OUTLINE_DIM
        val knob = if (active) Theme.KNOB else Theme.KNOB_DISABLED

        val knobSize = height - KNOB_INSET * 2
        val knobX = if (checked) x + width - KNOB_INSET - knobSize else x + KNOB_INSET

        nvgSurface(
            graphics, x, y, width, height,
            nvg = {
                val left = x.toFloat()
                val top = y.toFloat()
                val w = width.toFloat()
                val h = height.toFloat()
                // A pill, not the panel radius: the knob is round, and a 3px corner beside it reads as
                // a rounded box with a circle rattling inside it.
                val radius = h / 2f

                NVGUtils.drawRect(left, top, w, h, radius, Theme.c(track))
                if (hovered) NVGUtils.drawRect(left, top, w, h, radius, Theme.c(Theme.HOVER_TINT))
                NVGUtils.drawOutlineRect(
                    left + Theme.OUTLINE_INSET,
                    top + Theme.OUTLINE_INSET,
                    w - Theme.OUTLINE_THICKNESS,
                    h - Theme.OUTLINE_THICKNESS,
                    radius,
                    Theme.OUTLINE_THICKNESS,
                    Theme.c(outline),
                )
                NVGUtils.drawCircle(
                    knobX + knobSize / 2f,
                    top + h / 2f,
                    knobSize / 2f,
                    Theme.c(knob),
                )
            },
            vanilla = {
                graphics.fill(x, y, x + width, y + height, track)
                if (hovered) graphics.fill(x, y, x + width, y + height, Theme.HOVER_TINT)
                graphics.outline(x, y, width, height, outline)
                graphics.fill(knobX, y + KNOB_INSET, knobX + knobSize, y + KNOB_INSET + knobSize, knob)
            },
        )
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
}
