package com.maxisch.client.gui.widget

import com.maxisch.client.gui.Theme
import com.maxisch.client.gui.nvgSurface
import com.maxisch.client.render.render2d.NVGUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.CommonComponents

/**
 * A single hand-rolled -/+ stepper: one drawn track with the value centred and a clickable zone at
 * each end, replacing the three-widget minus-button/value-line/plus-button triple used elsewhere in
 * this codebase for the same job. Each end dims independently once [value] hits [min]/[max], instead
 * of a whole separate button going inactive.
 */
class StepperWidget(
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    var value: Int,
    var min: Int,
    var max: Int,
    var step: Int,
    var format: (Int) -> Component,
    var onChange: (Int) -> Unit,
) : AbstractWidget(x, y, w, h, CommonComponents.EMPTY) {

    private companion object {
        const val ZONE_WIDTH = 16

        // Chevrons, as the shell draws them. A hyphen and a plus read as arithmetic on the value;
        // these read as "step to the previous/next one", which is what the control does.
        val MINUS: Component = Component.literal("‹")
        val PLUS: Component = Component.literal("›")
    }

    private val font get() = Minecraft.getInstance().font

    override fun onClick(event: MouseButtonEvent, doubled: Boolean) {
        val local = event.x - x
        when {
            local < ZONE_WIDTH -> apply(-step)
            local > width - ZONE_WIDTH -> apply(step)
        }
    }

    private fun apply(delta: Int) {
        val next = (value + delta).coerceIn(min, max)
        if (next == value) return
        value = next
        onChange(value)
    }

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val hoveredMinus = value > min && mouseX in x..(x + ZONE_WIDTH) && mouseY in y..(y + height)
        val hoveredPlus = value < max &&
            mouseX in (x + width - ZONE_WIDTH)..(x + width) && mouseY in y..(y + height)

        nvgSurface(
            graphics, x, y, width, height,
            nvg = {
                val left = x.toFloat()
                val top = y.toFloat()
                val w = width.toFloat()
                val h = height.toFloat()
                val zone = ZONE_WIDTH.toFloat()

                NVGUtils.drawRect(left, top, w, h, Theme.RADIUS, Theme.c(Theme.TRACK))

                // Each end's hover is the track's own rounded shape clipped to that end, so the
                // outer corners stay round while the inner edge stays a straight cut.
                val hover = Theme.c(Theme.ZONE_HOVER)
                if (hoveredMinus) {
                    NVGUtils.pushScissor(left, top, zone, h)
                    NVGUtils.drawRect(left, top, w, h, Theme.RADIUS, hover)
                    NVGUtils.popScissor()
                }
                if (hoveredPlus) {
                    NVGUtils.pushScissor(left + w - zone, top, zone, h)
                    NVGUtils.drawRect(left, top, w, h, Theme.RADIUS, hover)
                    NVGUtils.popScissor()
                }

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
                graphics.fill(x, y, x + width, y + height, Theme.TRACK)
                graphics.outline(x, y, width, height, Theme.OUTLINE)
                if (hoveredMinus) graphics.fill(x, y, x + ZONE_WIDTH, y + height, Theme.ZONE_HOVER)
                if (hoveredPlus) {
                    graphics.fill(x + width - ZONE_WIDTH, y, x + width, y + height, Theme.ZONE_HOVER)
                }
            },
        )

        val minusColor = if (value > min) Theme.TEXT else Theme.TEXT_DIM
        val plusColor = if (value < max) Theme.TEXT else Theme.TEXT_DIM
        val textY = y + height / 2 - 4
        val minusX = x + ZONE_WIDTH / 2 - 2
        val plusX = x + width - ZONE_WIDTH / 2 - 2

        nvgSurface(
            graphics, x, y, width, height,
            nvg = {
                NVGUtils.drawText(MINUS.string, minusX.toFloat(), textY.toFloat(), Theme.TEXT_SIZE, Theme.c(minusColor), Theme.body)
                NVGUtils.drawText(PLUS.string, plusX.toFloat(), textY.toFloat(), Theme.TEXT_SIZE, Theme.c(plusColor), Theme.body)
                NVGUtils.drawCenteredText(
                    format(value).string,
                    (x + width / 2).toFloat(),
                    textY.toFloat(),
                    Theme.TEXT_SIZE,
                    Theme.c(Theme.TEXT),
                    Theme.body,
                )
            },
            vanilla = {
                graphics.text(font, MINUS, minusX, textY, minusColor)
                graphics.text(font, PLUS, plusX, textY, plusColor)
                graphics.centeredText(font, format(value), x + width / 2, textY, Theme.TEXT)
            },
        )
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
}
