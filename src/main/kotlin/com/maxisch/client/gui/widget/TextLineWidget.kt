package com.maxisch.client.gui.widget

import com.maxisch.client.gui.Theme
import com.maxisch.client.gui.nvgSurface
import com.maxisch.client.render.render2d.NVGUtils
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

        /**
         * NanoVG is told to align text from its top, as vanilla does, but Josefin carries more
         * leading above its caps than Minecraft's bitmap font. Without this the line sits visibly
         * lower than the vanilla widgets it shares a row with.
         */
        const val BASELINE_NUDGE = -1f
    }

    init {
        // Decorative, so invisible to hit-testing - see PanelWidget. A label is laid out at its
        // panel's full inner width, so where it shares a row with a control it would otherwise eat
        // the top HEIGHT pixels of that control's clicks.
        //
        // The cost is that an inactive widget is skipped by focus traversal and by narration, so
        // updateWidgetNarration below no longer speaks. These are static labels whose text the
        // control beside them also carries, which is the only reason that is acceptable.
        active = false
    }

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        nvgSurface(
            graphics, x, y, width, HEIGHT,
            nvg = {
                // `message.string` flattens any styling the component carries. These lines are all
                // plain translatables coloured by [color], so there is none to lose - a widget that
                // does carry styling must stay vanilla rather than come through here.
                NVGUtils.drawText(
                    message.string,
                    x.toFloat(),
                    y.toFloat() + BASELINE_NUDGE,
                    Theme.TEXT_SIZE,
                    Theme.c(color),
                    Theme.body,
                )
            },
            vanilla = { graphics.text(Minecraft.getInstance().font, message, x, y, color) },
        )
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        output.add(NarratedElementType.TITLE, message)
    }
}
