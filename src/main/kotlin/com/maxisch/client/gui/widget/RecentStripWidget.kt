package com.maxisch.client.gui.widget

import com.maxisch.client.gui.Theme
import com.maxisch.client.gui.nvgSurface
import com.maxisch.client.render.render2d.NVGUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.CommonComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block

/**
 * A row of recently used donors, click to arm.
 *
 * The block picker has had a recents strip for a while, but reaching it meant opening the picker -
 * two clicks and a full-screen takeover to re-pick something used a minute ago. The same strip on
 * the Brush tab makes the common case a single click, which is what the shell shows.
 *
 * Cells are the picker's size so the two read as the same control in two places.
 */
class RecentStripWidget(
    private val blocks: () -> List<Block>,
    private val current: () -> Block?,
    private val onPick: (Block) -> Unit,
) : AbstractWidget(0, 0, 0, CELL, CommonComponents.EMPTY) {

    companion object {
        const val CELL = 20

        private const val ITEM_INSET = 2
    }

    private fun indexAt(mouseX: Double, mouseY: Double): Int? {
        if (mouseY < y || mouseY >= y + CELL || mouseX < x) return null
        return ((mouseX - x) / CELL).toInt().takeIf { it in blocks().indices }
    }

    override fun onClick(event: MouseButtonEvent, doubled: Boolean) {
        indexAt(event.x, event.y)?.let { onPick(blocks()[it]) }
    }

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val recent = blocks()
        val armed = current()
        val hovered = indexAt(mouseX.toDouble(), mouseY.toDouble())

        nvgSurface(
            graphics, x, y, (recent.size * CELL).coerceAtLeast(1), CELL,
            nvg = {
                recent.forEachIndexed { index, block ->
                    val cellX = (x + index * CELL).toFloat()
                    NVGUtils.drawRect(cellX, y.toFloat(), CELL.toFloat(), CELL.toFloat(), Theme.RADIUS_SMALL, Theme.c(Theme.GROUND))
                    if (index == hovered) {
                        NVGUtils.drawRect(cellX, y.toFloat(), CELL.toFloat(), CELL.toFloat(), Theme.RADIUS_SMALL, Theme.c(Theme.HOVER))
                    }
                    // The armed donor keeps an amber ring, so the strip also answers "which one is
                    // loaded" without reading the bar.
                    NVGUtils.drawOutlineRect(
                        cellX + Theme.OUTLINE_INSET,
                        y + Theme.OUTLINE_INSET,
                        CELL - Theme.OUTLINE_THICKNESS,
                        CELL - Theme.OUTLINE_THICKNESS,
                        Theme.RADIUS_SMALL,
                        Theme.OUTLINE_THICKNESS,
                        Theme.c(if (block == armed) Theme.AMBER else Theme.RULE),
                    )
                }
            },
            vanilla = {
                recent.forEachIndexed { index, block ->
                    val cellX = x + index * CELL
                    graphics.fill(cellX, y, cellX + CELL, y + CELL, Theme.GROUND)
                    if (index == hovered) graphics.fill(cellX, y, cellX + CELL, y + CELL, Theme.HOVER)
                    graphics.outline(cellX, y, CELL, CELL, if (block == armed) Theme.AMBER else Theme.RULE)
                }
            },
        )

        // Items last and always vanilla - NanoVG cannot draw a block model.
        recent.forEachIndexed { index, block ->
            val stack = ItemStack(block)
            if (!stack.isEmpty) graphics.item(stack, x + index * CELL + ITEM_INSET, y + ITEM_INSET)
        }
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
}
