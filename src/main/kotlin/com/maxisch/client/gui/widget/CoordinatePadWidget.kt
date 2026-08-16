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
 * The panel the Area tab's two corner rows sit inside, plus the live volume readout under them.
 *
 * Selecting a box is the Area workflow - everything else on that tab acts on whatever this defines -
 * but until now it was two unlabelled rows of number fields among a dozen buttons, with the size
 * reported by a grey status line that looked like every other grey status line. This gives the
 * selection a frame of its own and puts the count where the eye lands.
 *
 * Decorative: it draws behind the fields and buttons the tab positions on top of it, so like
 * [PanelWidget] it must stay out of hit-testing or it would swallow their clicks.
 */
class CoordinatePadWidget(
    /** Long, not Int: a box of a few thousand blocks a side overflows an Int. */
    private val volume: () -> Long?,
) : AbstractWidget(0, 0, 0, 0, CommonComponents.EMPTY) {

    companion object {
        /** Height of the volume strip below the corner rows, which the tab must allow for. */
        const val VOLUME_HEIGHT = 16

        private const val PAD = 6
        private const val DASH = 3
        private const val DASH_GAP = 3
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
        val count = volume()
        // Namespaced under `pad` on purpose: `austrianpainter.area.volume` is already taken by the
        // status line's "Area is %s blocks", and a duplicate key silently resolves to whichever the
        // loader read last - which showed up as a literal %s on screen.
        val label = Component.translatable("austrianpainter.area.pad.volume").string
        val reading = count?.let { formatted(it) }
            ?: Component.translatable("austrianpainter.area.pad.none").string

        val ruleY = (y + height - VOLUME_HEIGHT).toFloat()
        val textY = y + height - VOLUME_HEIGHT + 4

        nvgSurface(
            graphics, x, y, width, height,
            nvg = {
                NVGUtils.drawRect(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), Theme.RADIUS, Theme.c(Theme.PANEL))
                NVGUtils.drawOutlineRect(
                    x + Theme.OUTLINE_INSET,
                    y + Theme.OUTLINE_INSET,
                    width - Theme.OUTLINE_THICKNESS,
                    height - Theme.OUTLINE_THICKNESS,
                    Theme.RADIUS,
                    Theme.OUTLINE_THICKNESS,
                    Theme.c(Theme.OUTLINE),
                )

                // Dashed, so the readout reads as a summary of the rows above rather than as another
                // row among them. NanoVG has no dash pattern, so it is drawn as segments.
                var dashX = x + PAD
                while (dashX < x + width - PAD) {
                    NVGUtils.drawRect(
                        dashX.toFloat(),
                        ruleY,
                        DASH.toFloat().coerceAtMost((x + width - PAD - dashX).toFloat()),
                        Theme.OUTLINE_THICKNESS,
                        Theme.c(Theme.RULE),
                    )
                    dashX += DASH + DASH_GAP
                }

                NVGUtils.drawText(label, (x + PAD).toFloat(), textY.toFloat(), Theme.TEXT_SIZE_SMALL, Theme.c(Theme.MUTED), Theme.body)
                // Cyan is the preview colour everywhere else in this mod; the volume is what a
                // preview would cover.
                val readingWidth = Theme.textWidth(reading, Theme.TEXT_SIZE, Theme.bold)
                NVGUtils.drawText(
                    reading,
                    x + width - PAD - readingWidth,
                    textY.toFloat(),
                    Theme.TEXT_SIZE,
                    Theme.c(if (count == null) Theme.DIM else Theme.CYAN),
                    Theme.bold,
                )
            },
            vanilla = {
                graphics.fill(x, y, x + width, y + height, Theme.PANEL)
                graphics.outline(x, y, width, height, Theme.OUTLINE)
                graphics.fill(x + PAD, ruleY.toInt(), x + width - PAD, ruleY.toInt() + 1, Theme.RULE)
                graphics.text(font, Component.literal(label), x + PAD, textY, Theme.MUTED)
                graphics.text(font, Component.literal(reading), x + width - PAD - font.width(reading), textY, Theme.CYAN)
            },
        )
    }

    /** Thousands separated, because a five-digit block count is unreadable run together. */
    private fun formatted(count: Long): String = "%,d".format(count)

    private val font get() = net.minecraft.client.Minecraft.getInstance().font

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
}
