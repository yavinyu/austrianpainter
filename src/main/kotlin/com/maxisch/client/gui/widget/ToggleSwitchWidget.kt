package com.maxisch.client.gui.widget

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
        const val TRACK_OFF = 0xFF3A3A3A.toInt()
        const val TRACK_DISABLED = 0xFF2A2A2A.toInt()
        const val KNOB = 0xFFE8E8E8.toInt()
        const val KNOB_DISABLED = 0xFF808080.toInt()
        const val OUTLINE = 0xFFFFFFFF.toInt()
        const val OUTLINE_DIM = 0xFF707070.toInt()
        const val HOVER_TINT = 0x30FFFFFF
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
            !active -> TRACK_DISABLED
            checked -> accent
            else -> TRACK_OFF
        }
        graphics.fill(x, y, x + width, y + height, track)
        if (hovered) graphics.fill(x, y, x + width, y + height, HOVER_TINT)
        graphics.outline(x, y, width, height, if (active) OUTLINE else OUTLINE_DIM)

        val knobSize = height - KNOB_INSET * 2
        val knobX = if (checked) x + width - KNOB_INSET - knobSize else x + KNOB_INSET
        graphics.fill(
            knobX,
            y + KNOB_INSET,
            knobX + knobSize,
            y + KNOB_INSET + knobSize,
            if (active) KNOB else KNOB_DISABLED,
        )
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
}
