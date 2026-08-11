package com.maxisch.client.gui.widget

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
        const val TRACK = 0x60000000
        const val ZONE_HOVER = 0x50FFFFFF
        const val OUTLINE = 0xFFFFFFFF.toInt()
        const val TEXT = 0xFFFFFFFF.toInt()
        const val TEXT_DIM = 0xFF808080.toInt()
        val MINUS: Component = Component.literal("-")
        val PLUS: Component = Component.literal("+")
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
        graphics.fill(x, y, x + width, y + height, TRACK)
        graphics.outline(x, y, width, height, OUTLINE)

        val hoveredMinus = mouseX in x..(x + ZONE_WIDTH) && mouseY in y..(y + height)
        val hoveredPlus = mouseX in (x + width - ZONE_WIDTH)..(x + width) && mouseY in y..(y + height)
        if (value > min && hoveredMinus) graphics.fill(x, y, x + ZONE_WIDTH, y + height, ZONE_HOVER)
        if (value < max && hoveredPlus) {
            graphics.fill(x + width - ZONE_WIDTH, y, x + width, y + height, ZONE_HOVER)
        }

        val minusColor = if (value > min) TEXT else TEXT_DIM
        val plusColor = if (value < max) TEXT else TEXT_DIM
        val textY = y + height / 2 - 4
        graphics.text(font, MINUS, x + ZONE_WIDTH / 2 - 2, textY, minusColor)
        graphics.text(font, PLUS, x + width - ZONE_WIDTH / 2 - 2, textY, plusColor)
        graphics.centeredText(font, format(value), x + width / 2, textY, TEXT)
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
}
