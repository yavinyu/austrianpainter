package com.maxisch.client.gui.widget

import com.maxisch.client.gui.Theme
import com.maxisch.client.gui.nvgSurface
import com.maxisch.client.render.render2d.NVGUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

/**
 * A titled group of controls: the shell's `card`.
 *
 * Differs from [PanelWidget], which it is meant to replace: a card carries its own header rather
 * than needing a separate label widget above it, has no coloured accent bar, and takes an optional
 * right-aligned count. Grouping controls under a named card is most of what separates the mockup
 * from a tab that is a flat stack of buttons.
 *
 * Decorative. Add it before the controls it groups - render order is add() order - and like every
 * drawing-only widget here it stays out of hit-testing.
 */
class CardWidget(
    private val title: Component,
    /** Right-aligned in the header, e.g. a row count. Null draws nothing. */
    private val count: () -> Component? = { null },
) : AbstractWidget(0, 0, 0, 0, CommonComponents.EMPTY) {

    companion object {
        /** Height of the header strip; controls inside a card start below this. */
        const val HEADER_HEIGHT = 14

        /** Inset for content inside the card, which callers must allow for when laying out. */
        const val PAD = 6
    }

    init {
        active = false
    }

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val heading = title.string.uppercase()
        val trailing = count()?.string

        nvgSurface(
            graphics, x, y, width, height,
            nvg = {
                NVGUtils.drawRect(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), Theme.RADIUS, Theme.c(Theme.SURFACE))
                NVGUtils.drawOutlineRect(
                    x + Theme.OUTLINE_INSET,
                    y + Theme.OUTLINE_INSET,
                    width - Theme.OUTLINE_THICKNESS,
                    height - Theme.OUTLINE_THICKNESS,
                    Theme.RADIUS,
                    Theme.OUTLINE_THICKNESS,
                    Theme.c(Theme.RULE),
                )
                val headerTextY = y + (HEADER_HEIGHT - Theme.textHeight(Theme.TEXT_SIZE_SMALL)) / 2f
                NVGUtils.drawText(
                    heading,
                    (x + PAD).toFloat(),
                    headerTextY,
                    Theme.TEXT_SIZE_SMALL,
                    Theme.c(Theme.DIM),
                    Theme.body,
                    Theme.LABEL_TRACKING,
                )
                trailing?.let {
                    val trailingWidth = Theme.textWidth(it, Theme.TEXT_SIZE_SMALL)
                    NVGUtils.drawText(
                        it,
                        x + width - PAD - trailingWidth,
                        headerTextY,
                        Theme.TEXT_SIZE_SMALL,
                        Theme.c(Theme.MUTED),
                        Theme.body,
                    )
                }
            },
            vanilla = {
                graphics.fill(x, y, x + width, y + height, Theme.SURFACE)
                graphics.outline(x, y, width, height, Theme.RULE)
                graphics.text(font, Component.literal(heading), x + PAD, y + 4, Theme.DIM)
                trailing?.let {
                    graphics.text(font, Component.literal(it), x + width - PAD - font.width(it), y + 4, Theme.MUTED)
                }
            },
        )
    }

    private val font get() = net.minecraft.client.Minecraft.getInstance().font

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
}
