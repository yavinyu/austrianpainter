package com.maxisch.client.gui.tab

import com.maxisch.client.keybind.KeyHints
import com.maxisch.client.gui.screen.PainterScreen
import com.maxisch.client.gui.Theme
import com.maxisch.client.gui.widget.ActButtonWidget
import com.maxisch.client.gui.widget.CardWidget
import com.maxisch.client.gui.widget.RowContent
import com.maxisch.client.gui.widget.RowListWidget
import com.maxisch.client.gui.widget.TextLineWidget
import com.maxisch.paint.PaintHistory
import com.maxisch.paint.PaintStorage
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.network.chat.Component

/**
 * What undo will actually undo.
 *
 * Every history step already carries a human label and a cost; until now the only thing the player
 * could see was a depth count on the footer button, which made undo a guess. The list is read-only -
 * steps can only be taken off the top, so there is nothing to click.
 */
class HistoryTab(private val screen: PainterScreen) : ApTab("austrianpainter.tab.history") {

    private companion object {
        const val MARGIN = 8
        const val ROW_HEIGHT = 14
        const val BUTTON_HEIGHT = 16
        const val GAP = 4
        const val GREY = 0xFFA0A0A0.toInt()
    }

    private val headerLine = add(TextLineWidget(0, 0, 0, GREY))

    private val undoButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.history.undo")) {
            screen.status(PaintStorage.undo())
            refresh()
        }
            .width(110)
            .tooltip(KeyHints.undoTooltip())
            .build(),
    )

    private val redoButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.history.redo")) {
            screen.status(PaintStorage.redo())
            refresh()
        }
            .width(110)
            .tooltip(KeyHints.redoTooltip())
            .build(),
    )

    private val listCard = add(
        CardWidget(Component.translatable("austrianpainter.card.history")) {
            Component.literal(list.rows.size.toString())
        },
    )

    private val list = add(
        RowListWidget<PaintHistory.Step>(0, 0, 0, 0, ROW_HEIGHT).apply {
            emptyMessage = { Component.translatable("austrianpainter.history.empty") }
            drawRow = { graphics, step, x, y, _ ->
                // The newest step is the one undo takes, so it is worth calling out by name.
                val newest = rows.firstOrNull() === step
                val label = Component.translatable(
                    if (newest) "austrianpainter.history.row_next" else "austrianpainter.history.row",
                    step.label,
                    step.cost,
                )
                RowContent.label(
                    graphics, x + 4, y, ROW_HEIGHT, x + width,
                    text = label,
                    colour = if (newest) 0xFFFFFF55.toInt() else Theme.INK,
                )
            }
        },
    )

    // ------------------------------------------------------------------ layout

    override fun layout(area: ScreenRectangle) {
        val x = area.left() + MARGIN
        val contentWidth = area.width() - MARGIN * 2
        var y = area.top() + GAP

        headerLine.setRectangle(contentWidth, TextLineWidget.HEIGHT, x, y)
        y += TextLineWidget.HEIGHT + GAP

        undoButton.setRectangle(undoButton.width, BUTTON_HEIGHT, x, y)
        redoButton.setRectangle(redoButton.width, BUTTON_HEIGHT, x + undoButton.width + GAP, y)
        y += BUTTON_HEIGHT + GAP * 2

        val listHeight = (area.bottom() - y - GAP).coerceAtLeast(ROW_HEIGHT)
        listCard.setRectangle(contentWidth, listHeight, x, y)
        list.setRectangle(
            contentWidth - CardWidget.PAD * 2,
            (listHeight - CardWidget.HEADER_HEIGHT - CardWidget.PAD).coerceAtLeast(ROW_HEIGHT),
            x + CardWidget.PAD,
            y + CardWidget.HEADER_HEIGHT,
        )
    }

    // ------------------------------------------------------------------ state

    override fun refresh() {
        list.rows = PaintStorage.undoStack()
        headerLine.message = Component.translatable(
            "austrianpainter.history.header",
            PaintStorage.undoDepth,
            PaintHistory.MAX_DEPTH,
            PaintStorage.redoDepth,
        )
        undoButton.active = PaintStorage.canUndo
        redoButton.active = PaintStorage.canRedo
    }
}
