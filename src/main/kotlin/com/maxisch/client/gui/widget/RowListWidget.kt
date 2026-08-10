package com.maxisch.client.gui.widget

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

/**
 * The scrolling row list every part of the paint UI is built around.
 *
 * A widget rather than something the screen draws itself, because a tab hands its children out as
 * [AbstractWidget]s ([net.minecraft.client.gui.components.tabs.Tab.visitChildren]) - anything drawn
 * at screen level would not survive a tab switch. It also puts the scroll clamp, the hit test and
 * the scissor in one place instead of once per list.
 *
 * Rows are drawn by [drawRow]; the widget owns the background, the hover and selection fills, and
 * the clipping around them.
 */
class RowListWidget<T>(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    private val rowHeight: Int,
) : AbstractWidget(x, y, width, height, CommonComponents.EMPTY) {

    companion object {
        const val BACKGROUND = 0x60000000
        const val HOVER = 0x60FFFFFF
        const val SELECTED = 0x8055FF55.toInt()
        const val DELETE_HOVER = 0x60FF5555
        const val EMPTY_TEXT = 0xFF808080.toInt()
    }

    var rows: List<T> = emptyList()
        set(value) {
            field = value
            clampScroll()
        }

    /** Shown in place of the list while it is empty; usually says why rather than just "nothing". */
    var emptyMessage: () -> Component = { CommonComponents.EMPTY }

    /** Colour of the empty message, so a refusal can be red without the caller drawing it. */
    var emptyColor: Int = EMPTY_TEXT

    var hoverColor: Int = HOVER

    /** Rows this returns true for get the selection fill instead of the hover fill. */
    var isSelected: (T) -> Boolean = { false }

    var onRowClick: ((T) -> Unit)? = null

    /**
     * Click with the row's index and the modifiers held, for lists that select more than one row.
     * Takes precedence over [onRowClick] when set, so the single-select lists need no changes.
     */
    var onRowSelect: ((row: T, index: Int, ctrl: Boolean, shift: Boolean) -> Unit)? = null

    /**
     * Wheel over a row. Returning true consumes the event so the list does not also scroll - that
     * is how the palette edits a weight under the cursor.
     */
    var onRowScroll: ((T, Int) -> Boolean)? = null

    /** Only shown while the cursor is actually over a row. */
    var hoverTooltip: (() -> Component)? = null

    /** `(graphics, row, x, y, hovered)`. The fills and the clipping are already done. */
    var drawRow: (GuiGraphicsExtractor, T, Int, Int, Boolean) -> Unit = { _, _, _, _, _ -> }

    private var scroll = 0

    /** How many rows fit; at least one, so a very short list area still shows something. */
    val visibleRows: Int
        get() = (height / rowHeight).coerceAtLeast(1)

    private val maxScroll: Int
        get() = (rows.size - visibleRows).coerceAtLeast(0)

    private fun clampScroll() {
        scroll = scroll.coerceIn(0, maxScroll)
    }

    /** Index into [rows], not into the visible window; null when the cursor is off a row. */
    fun indexAt(mouseX: Double, mouseY: Double): Int? {
        if (mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + height) return null
        val offset = ((mouseY - y) / rowHeight).toInt()
        if (offset !in 0 until visibleRows) return null
        return (scroll + offset).takeIf { it in rows.indices }
    }

    fun rowAt(mouseX: Double, mouseY: Double): T? = indexAt(mouseX, mouseY)?.let { rows[it] }

    // ------------------------------------------------------------------ input

    override fun onClick(event: MouseButtonEvent, doubled: Boolean) {
        val index = indexAt(event.x, event.y) ?: return
        val row = rows[index]

        val select = onRowSelect
        if (select != null) {
            select(row, index, held(InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL), held(InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT))
            return
        }
        onRowClick?.invoke(row)
    }

    /** The click event carries no modifiers in 26.2, so ask the window directly. */
    private fun held(left: Int, right: Int): Boolean {
        val window = net.minecraft.client.Minecraft.getInstance().window
        return InputConstants.isKeyDown(window, left) || InputConstants.isKeyDown(window, right)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val delta = if (scrollY > 0) -1 else if (scrollY < 0) 1 else 0
        if (delta == 0) return false

        val handler = onRowScroll
        if (handler != null) {
            val row = rowAt(mouseX, mouseY)
            // -delta so a scroll up raises the value, which is the way round every other slider goes.
            if (row != null && handler(row, -delta)) return true
        }

        scroll = (scroll + delta).coerceIn(0, maxScroll)
        return true
    }

    // ------------------------------------------------------------------ render

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        // Clamp here as well as on assignment: the caller can resize the widget between frames,
        // which changes visibleRows and so the scroll ceiling.
        clampScroll()

        graphics.fill(x - 2, y - 2, x + width + 2, y + height + 2, BACKGROUND)

        if (rows.isEmpty()) {
            graphics.text(font, emptyMessage(), x + 2, y + 3, emptyColor)
            return
        }

        graphics.enableScissor(x, y, x + width, y + height)
        for (index in 0 until visibleRows) {
            val row = rows.getOrNull(scroll + index) ?: break
            val rowY = y + index * rowHeight
            val hovered = mouseX in x..(x + width) && mouseY in rowY until rowY + rowHeight

            if (isSelected(row)) {
                graphics.fill(x, rowY, x + width, rowY + rowHeight, SELECTED)
            } else if (hovered) {
                graphics.fill(x, rowY, x + width, rowY + rowHeight, hoverColor)
            }

            drawRow(graphics, row, x, rowY, hovered)
        }
        graphics.disableScissor()

        hoverTooltip?.let { tooltip ->
            if (rowAt(mouseX.toDouble(), mouseY.toDouble()) != null) {
                graphics.setTooltipForNextFrame(tooltip(), mouseX, mouseY)
            }
        }
    }

    /** Rows carry no fixed label, so there is nothing stable to narrate here. */
    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit

    private val font get() = net.minecraft.client.Minecraft.getInstance().font
}
