package com.maxisch.client.gui.widget

import com.maxisch.client.gui.Theme
import com.maxisch.client.gui.nvgSurface
import com.maxisch.client.render.render2d.NVGUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * A flat bordered button in the frame's own style: a hairline box that takes its colour from what
 * the action does, rather than vanilla's raised nine-slice.
 *
 * Every button in the paint UI, frame and tabs alike. Vanilla's raised grey slab is the one thing
 * that cannot be made to sit quietly among hairline-ruled cards, and there are sixty-odd of them.
 */
class ActButtonWidget(
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    message: Component,
    private val variant: Variant,
    private val onPress: () -> Unit,
) : AbstractWidget(x, y, w, h, message) {

    /** What the action means, which is the only thing that decides the colour. */
    enum class Variant(val text: Int, val border: Int, val background: Int) {
        /** The action the screen expects you to take. */
        PRIMARY(Theme.AMBER, 0x80FFAA00.toInt(), 0x17FFAA00),

        /** Everything ordinary. */
        DEFAULT(Theme.INK, Theme.RULE, Theme.RAISE),

        /** Removes paint or rules. */
        DANGER(Theme.RED, 0x59FF6B6B, Theme.RAISE),

        /** Present, but not what you came here for. */
        GHOST(Theme.MUTED, Theme.RULE_SOFT, Theme.RAISE),
    }

    companion object {
        /** Vanilla's default button height, which every `doLayout` in this mod already assumes. */
        const val DEFAULT_HEIGHT = 16

        const val DEFAULT_WIDTH = 100

        /**
         * Deliberately shaped like [net.minecraft.client.gui.components.Button.builder].
         *
         * The tabs build sixty-odd buttons through that API. Matching it means the migration is a
         * change of type rather than a rewrite of every call site, and a call site that is only
         * `.width(...).build()` reads identically before and after.
         */
        fun builder(message: Component, onPress: () -> Unit): Builder = Builder(message, onPress)
    }

    /** See [builder]. */
    class Builder(private val message: Component, private val onPress: () -> Unit) {

        private var x = 0
        private var y = 0
        private var width = DEFAULT_WIDTH
        private var height = DEFAULT_HEIGHT
        private var variant = Variant.DEFAULT
        private var tooltip: Tooltip? = null

        fun width(value: Int): Builder = apply { width = value }

        fun bounds(x: Int, y: Int, width: Int, height: Int): Builder = apply {
            this.x = x
            this.y = y
            this.width = width
            this.height = height
        }

        fun tooltip(value: Tooltip): Builder = apply { tooltip = value }

        /** What the action means; see [Variant]. Absent from vanilla's builder, hence the default. */
        fun variant(value: Variant): Builder = apply { variant = value }

        fun build(): ActButtonWidget =
            ActButtonWidget(x, y, width, height, message, variant, onPress).also { button ->
                tooltip?.let(button::setTooltip)
            }
    }

    private val font get() = Minecraft.getInstance().font

    override fun onClick(event: MouseButtonEvent, doubled: Boolean) {
        onPress()
    }

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val hovered = active && isHovered
        val text = if (active) variant.text else Theme.DIM
        val border = if (active) variant.border else Theme.RULE_SOFT

        nvgSurface(
            graphics, x, y, width, height,
            nvg = {
                val left = x.toFloat()
                val top = y.toFloat()
                val w = width.toFloat()
                val h = height.toFloat()

                NVGUtils.drawRect(left, top, w, h, Theme.RADIUS_SMALL, Theme.c(variant.background))
                if (hovered) NVGUtils.drawRect(left, top, w, h, Theme.RADIUS_SMALL, Theme.c(Theme.HOVER_TINT))
                NVGUtils.drawOutlineRect(
                    left + Theme.OUTLINE_INSET,
                    top + Theme.OUTLINE_INSET,
                    w - Theme.OUTLINE_THICKNESS,
                    h - Theme.OUTLINE_THICKNESS,
                    Theme.RADIUS_SMALL,
                    Theme.OUTLINE_THICKNESS,
                    Theme.c(border),
                )
            },
            vanilla = {
                graphics.fill(x, y, x + width, y + height, variant.background)
                if (hovered) graphics.fill(x, y, x + width, y + height, Theme.HOVER_TINT)
                graphics.outline(x, y, width, height, border)
            },
        )

        nvgSurface(
            graphics, x, y, width, height,
            nvg = {
                val labelY = y + (height - Theme.textHeight()) / 2f
                NVGUtils.drawCenteredText(
                    message.string,
                    (x + width / 2).toFloat(),
                    labelY,
                    Theme.TEXT_SIZE,
                    Theme.c(text),
                    Theme.body,
                )
            },
            vanilla = {
                val labelY = y + (height - font.lineHeight) / 2 + 1
                graphics.centeredText(font, message, x + width / 2, labelY, text)
            },
        )
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        defaultButtonNarrationText(output)
    }
}
