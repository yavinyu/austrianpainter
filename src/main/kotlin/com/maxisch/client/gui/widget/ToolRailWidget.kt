package com.maxisch.client.gui.widget

import com.maxisch.client.gui.Theme
import com.maxisch.client.gui.nvgSurface
import com.maxisch.client.render.render2d.NVGUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

/**
 * The vertical tool rail down the left of the paint screen: one row per tab, the selected one marked
 * by an amber bar against the frame's edge.
 *
 * Replaces [net.minecraft.client.gui.components.tabs.TabNavigationBar], which is horizontal, is
 * styled as vanilla's inventory-tab strip, and spends the full screen width on six labels. The rail
 * gives that width back to the tools and leaves room for the armed bar above it.
 *
 * Selection is not held here: the screen owns which tab is current, and this reads it back through
 * [selectedIndex] so a tab switched from anywhere else still lights the right row.
 */
class ToolRailWidget(
    private val titles: List<Component>,
    /** Per-tool readout under the label - a rule count, a volume, a floor. Null draws nothing. */
    private val counts: List<() -> String?>,
    var selectedIndex: () -> Int,
    private val onSelect: (Int) -> Unit,
) : AbstractWidget(0, 0, WIDTH, 0, CommonComponents.EMPTY) {

    companion object {
        const val WIDTH = 116

        /** Two lines: the tool's name over its count. */
        const val ROW_HEIGHT = 32

        /** Width of the amber bar marking the selected row. */
        private const val MARKER = 2

        private const val LABEL_X = 12
    }

    private val font get() = Minecraft.getInstance().font

    /** Index of the row under the cursor, or null when the cursor is off the rail or past the rows. */
    private fun indexAt(mouseX: Double, mouseY: Double): Int? {
        if (mouseX < x || mouseX > x + width || mouseY < y) return null
        val index = ((mouseY - y) / ROW_HEIGHT).toInt()
        return index.takeIf { it in titles.indices }
    }

    override fun onClick(event: MouseButtonEvent, doubled: Boolean) {
        indexAt(event.x, event.y)?.let(onSelect)
    }

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val selected = selectedIndex()
        val hovered = indexAt(mouseX.toDouble(), mouseY.toDouble())

        nvgSurface(
            graphics, x, y, width, height,
            nvg = {
                // Square, not rounded: the rail is frame furniture, flush with the screen edge and
                // with the bar above it. A radius here would leave slivers of ground in the corners.
                NVGUtils.drawRect(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), Theme.c(Theme.SURFACE))
                NVGUtils.drawRect(
                    (x + width - Theme.OUTLINE_THICKNESS),
                    y.toFloat(),
                    Theme.OUTLINE_THICKNESS,
                    height.toFloat(),
                    Theme.c(Theme.RULE),
                )

                titles.indices.forEach { index ->
                    val rowY = (y + index * ROW_HEIGHT).toFloat()
                    when {
                        index == selected -> {
                            NVGUtils.drawRect(x.toFloat(), rowY, width.toFloat(), ROW_HEIGHT.toFloat(), Theme.c(Theme.HOVER))
                            NVGUtils.drawRect(x.toFloat(), rowY, MARKER.toFloat(), ROW_HEIGHT.toFloat(), Theme.c(Theme.AMBER))
                        }
                        index == hovered -> {
                            NVGUtils.drawRect(x.toFloat(), rowY, width.toFloat(), ROW_HEIGHT.toFloat(), Theme.c(Theme.HOVER_TINT))
                        }
                    }
                }
            },
            vanilla = {
                graphics.fill(x, y, x + width, y + height, Theme.SURFACE)
                graphics.fill(x + width - 1, y, x + width, y + height, Theme.RULE)
                titles.indices.forEach { index ->
                    val rowY = y + index * ROW_HEIGHT
                    when {
                        index == selected -> {
                            graphics.fill(x, rowY, x + width, rowY + ROW_HEIGHT, Theme.HOVER)
                            graphics.fill(x, rowY, x + MARKER, rowY + ROW_HEIGHT, Theme.AMBER)
                        }
                        index == hovered -> graphics.fill(x, rowY, x + width, rowY + ROW_HEIGHT, Theme.HOVER_TINT)
                    }
                }
            },
        )

        // Text is a second surface rather than part of the one above: the fills have to be laid down
        // for every row before any label is drawn, or a later row's fill would cover an earlier
        // row's text.
        // Name and count are baselined as a pair inside the row rather than centred independently,
        // so a tool with no count does not sit visibly higher than its neighbours.
        val nameY = { index: Int -> y + index * ROW_HEIGHT + 8 }
        val countY = { index: Int -> y + index * ROW_HEIGHT + 19 }

        nvgSurface(
            graphics, x, y, width, height,
            nvg = {
                titles.forEachIndexed { index, title ->
                    NVGUtils.drawText(
                        title.string,
                        (x + LABEL_X).toFloat(),
                        nameY(index).toFloat(),
                        Theme.TEXT_SIZE,
                        Theme.c(labelColour(index, selected, hovered)),
                        Theme.body,
                    )
                    counts.getOrNull(index)?.invoke()?.let { count ->
                        NVGUtils.drawText(
                            count,
                            (x + LABEL_X).toFloat(),
                            countY(index).toFloat(),
                            Theme.TEXT_SIZE_SMALL,
                            Theme.c(if (index == selected) Theme.AMBER else Theme.DIM),
                            Theme.body,
                        )
                    }
                }
            },
            vanilla = {
                titles.forEachIndexed { index, title ->
                    graphics.text(font, title, x + LABEL_X, nameY(index), labelColour(index, selected, hovered))
                    counts.getOrNull(index)?.invoke()?.let { count ->
                        graphics.text(
                            font,
                            Component.literal(count),
                            x + LABEL_X,
                            countY(index),
                            if (index == selected) Theme.AMBER else Theme.DIM,
                        )
                    }
                }
            },
        )
    }

    private fun labelColour(index: Int, selected: Int, hovered: Int?): Int = when (index) {
        selected -> Theme.INK
        hovered -> Theme.MUTED
        else -> Theme.DIM
    }

    /** The tab buttons this replaces narrate themselves; the rail is announced by the screen instead. */
    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
}
