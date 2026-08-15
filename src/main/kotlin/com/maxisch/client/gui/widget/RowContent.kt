package com.maxisch.client.gui.widget

import com.maxisch.client.gui.Theme
import com.maxisch.client.gui.nvgSurface
import com.maxisch.client.render.render2d.NVGUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block

/**
 * The shell's list-row convention, in one place: `▎ icon → icon  name   qty`.
 *
 * Every list in this mod described a rule as a sentence - "Stone Bricks renders as Deepslate Tiles" -
 * which reads fine once and badly forty times: the eye has to parse each line to find the two blocks
 * it cares about. The shell shows the pair as icons with an arrow between them and pushes any count
 * to the right edge, so a column of rules scans as a column rather than as prose.
 *
 * Kept as functions rather than a widget because rows are drawn by their list's `drawRow` lambda,
 * which owns the row's position and hover state.
 */
object RowContent {

    const val ACCENT_WIDTH = 3
    const val ACCENT_INSET = 2
    private const val ACCENT_RADIUS = 1.5f

    /** Left edge of the first icon, clear of the accent bar. */
    const val ICON_X = 6

    private const val ICON_SIZE = 16
    private const val ARROW_WIDTH = 8
    private const val PAD = 6

    private val font get() = Minecraft.getInstance().font

    /** The group bar down a row's left edge; inset so consecutive rows read as separate bars. */
    fun accent(graphics: GuiGraphicsExtractor, x: Int, y: Int, rowHeight: Int, colour: Int) {
        nvgSurface(
            graphics, x, y, ACCENT_WIDTH, rowHeight,
            nvg = {
                NVGUtils.drawRect(
                    x.toFloat(),
                    (y + ACCENT_INSET).toFloat(),
                    ACCENT_WIDTH.toFloat(),
                    (rowHeight - ACCENT_INSET * 2).toFloat(),
                    ACCENT_RADIUS,
                    Theme.c(colour),
                )
            },
            vanilla = { graphics.fill(x, y + ACCENT_INSET, x + ACCENT_WIDTH, y + rowHeight - ACCENT_INSET, colour) },
        )
    }

    /**
     * Source and target icons with an arrow between them; returns the x the label should start at.
     *
     * [target] may be null for a rule that leaves its source alone - the arrow and second icon are
     * then omitted rather than drawn empty, because "no target" is a real state and an empty slot
     * would read as a missing texture.
     */
    fun pair(
        graphics: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        rowHeight: Int,
        source: Block,
        target: Block?,
    ): Int {
        var cursor = x + ICON_X
        val iconY = y + (rowHeight - ICON_SIZE) / 2

        icon(graphics, source, cursor, iconY)
        cursor += ICON_SIZE

        if (target != null) {
            arrow(graphics, cursor, y + rowHeight / 2)
            cursor += ARROW_WIDTH
            icon(graphics, target, cursor, iconY)
            cursor += ICON_SIZE
        }
        return cursor + PAD
    }

    private fun icon(graphics: GuiGraphicsExtractor, block: Block, x: Int, y: Int) {
        val stack = ItemStack(block)
        if (!stack.isEmpty) {
            graphics.item(stack, x, y)
        } else {
            // A block with no item still occupies its slot, so the row does not reflow.
            graphics.outline(x + 3, y + 3, ICON_SIZE - 6, ICON_SIZE - 6, Theme.DIM)
        }
    }

    private fun arrow(graphics: GuiGraphicsExtractor, x: Int, centreY: Int) {
        nvgSurface(
            graphics, x, centreY - 4, ARROW_WIDTH, 8,
            nvg = {
                NVGUtils.drawText("›", (x + 2).toFloat(), (centreY - 6).toFloat(), Theme.TEXT_SIZE, Theme.c(Theme.DIM), Theme.body)
            },
            vanilla = { graphics.text(font, Component.literal("›"), x + 2, centreY - 4, Theme.DIM) },
        )
    }

    /** Row label at [x], with [trailing] pushed to the row's right edge when present. */
    fun label(
        graphics: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        rowHeight: Int,
        rowRight: Int,
        text: Component,
        trailing: Component? = null,
        colour: Int = Theme.INK,
    ) {
        val textY = y + (rowHeight - font.lineHeight) / 2 + 1
        val trailingWidth = trailing?.let { Theme.textWidth(it.string) } ?: 0f
        val available = (rowRight - PAD - trailingWidth - x).toInt()

        nvgSurface(
            graphics, x, y, (rowRight - x).coerceAtLeast(1), rowHeight,
            nvg = {
                NVGUtils.pushScissor(x.toFloat(), y.toFloat(), available.coerceAtLeast(1).toFloat(), rowHeight.toFloat())
                NVGUtils.drawText(text.string, x.toFloat(), textY.toFloat(), Theme.TEXT_SIZE, Theme.c(colour), Theme.body)
                NVGUtils.popScissor()
                trailing?.let {
                    NVGUtils.drawText(
                        it.string,
                        rowRight - PAD - trailingWidth,
                        textY.toFloat(),
                        Theme.TEXT_SIZE_SMALL,
                        Theme.c(Theme.MUTED),
                        Theme.body,
                    )
                }
            },
            vanilla = {
                graphics.text(font, text, x, textY, colour)
                trailing?.let {
                    graphics.text(font, it, rowRight - PAD - font.width(it), textY, Theme.MUTED)
                }
            },
        )
    }
}
