package com.maxisch.client.gui.tab

import com.maxisch.client.gui.BlockPickerScreen
import com.maxisch.client.gui.PainterScreen
import com.maxisch.client.gui.widget.RowListWidget
import com.maxisch.client.gui.widget.TextLineWidget
import com.maxisch.paint.session.PaintArea
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/**
 * Box selection workflow: set two corners, see what is actually inside the box, then repaint one of
 * those block types. The replace is flattened to positions straight away, so it behaves exactly like
 * a very large brush stroke and nothing new has to be persisted.
 *
 * Owns the widgets and layout only; the scan/apply rules live in [AreaScanLogic].
 */
class AreaTab(private val screen: PainterScreen) : ApTab("austrianpainter.tab.area") {

    private companion object {
        const val MARGIN = 8
        const val ROW_HEIGHT = 14
        const val BUTTON_HEIGHT = 20
        const val GAP = 4

        const val LABEL_WIDTH = 56
        const val FIELD_WIDTH = 46

        const val GREY = 0xFFA0A0A0.toInt()
        const val YELLOW = 0xFFFFFF55.toInt()
        const val RED = 0xFFFF5555.toInt()

        /** Corners update live, but a scan of a large box should not run on every keystroke. */
        const val RESCAN_DELAY_TICKS = 8
    }

    private val logic = AreaScanLogic(screen)

    private var rescanIn = -1
    private var suppressResponder = false

    private val font get() = Minecraft.getInstance().font

    private val cornerOneLabel = add(TextLineWidget(0, 0, LABEL_WIDTH, GREY)).also {
        it.message = Component.translatable("austrianpainter.area.corner_one")
    }
    private val cornerTwoLabel = add(TextLineWidget(0, 0, LABEL_WIDTH, GREY)).also {
        it.message = Component.translatable("austrianpainter.area.corner_two")
    }

    private val fields1 = buildFields(first = true)
    private val fields2 = buildFields(first = false)

    private val useLookedAt1 = add(useLookedAtButton(first = true))
    private val useLookedAt2 = add(useLookedAtButton(first = false))

    private val sizeLine = add(TextLineWidget(0, 0, 0, GREY))
    private val sourceLine = add(TextLineWidget(0, 0, 0, YELLOW))
    private val donorLine = add(TextLineWidget(0, 0, 0, GREY))

    private val donorButton = add(
        Button.builder(Component.translatable("austrianpainter.pick_donor")) {
            Minecraft.getInstance().setScreenAndShow(BlockPickerScreen(screen) { PaintArea.donor = it })
        }.width(104).build(),
    )

    private val rescanButton = add(
        Button.builder(Component.translatable("austrianpainter.area.rescan")) { rescan() }.width(70).build(),
    )

    private val clearSelectionButton = add(
        Button.builder(Component.translatable("austrianpainter.area.clear_selection")) {
            PaintArea.clearSelection()
            syncFields()
            rescan()
        }.width(110).build(),
    )

    private val replaceButton = add(
        Button.builder(Component.translatable("austrianpainter.area.replace")) {
            logic.replace()
            rescan()
        }.width(110).build(),
    )

    private val replaceRandomButton = add(
        Button.builder(Component.translatable("austrianpainter.area.replace_random")) {
            logic.replaceRandom()
            rescan()
        }.width(130).build(),
    )

    private val rerollButton = add(
        Button.builder(Component.translatable("austrianpainter.area.reroll")) {
            logic.reroll()
            refreshButtons()
        }.width(80).build(),
    )

    private val clearPaintedButton = add(
        Button.builder(Component.translatable("austrianpainter.area.clear_painted")) { logic.clearPainted() }
            .width(150).build(),
    )

    private val list = add(
        RowListWidget<AreaScanLogic.Row>(0, 0, 0, 0, ROW_HEIGHT).apply {
            emptyMessage = { logic.emptyText() }
            hoverTooltip = { Component.translatable("austrianpainter.area.pick_source") }
            isSelected = { row ->
                when (row) {
                    is AreaScanLogic.Row.All -> PaintArea.sourceAll
                    is AreaScanLogic.Row.Type -> !PaintArea.sourceAll && row.block == PaintArea.source
                }
            }
            onRowClick = { row ->
                when (row) {
                    is AreaScanLogic.Row.All -> {
                        PaintArea.sourceAll = true
                        PaintArea.source = null
                    }

                    is AreaScanLogic.Row.Type -> {
                        PaintArea.sourceAll = false
                        PaintArea.source = row.block
                    }
                }
                refreshButtons()
            }
            drawRow = { graphics, row, x, y, _ ->
                val label = when (row) {
                    is AreaScanLogic.Row.All -> Component.translatable("austrianpainter.area.row_all", row.count)
                    is AreaScanLogic.Row.Type -> {
                        val stack = ItemStack(row.block)
                        if (!stack.isEmpty) graphics.item(stack, x + 2, y - 1)
                        Component.translatable("austrianpainter.area.row", row.block.name, row.count)
                    }
                }
                graphics.text(font, label, x + 22, y + 3, 0xFFFFFFFF.toInt())
            }
        },
    )

    // ------------------------------------------------------------------ corner fields

    private fun buildFields(first: Boolean): Array<EditBox> {
        val boxes = Array(3) { axis ->
            val box = EditBox(font, 0, 0, FIELD_WIDTH, 18, Component.translatable(axisKey(axis)))
            box.setMaxLength(8)
            box.setHint(Component.translatable(axisKey(axis)))
            add(box)
        }
        // The responder is wired after construction so filling the array cannot re-enter readCorner.
        boxes.forEach { box -> box.setResponder { if (!suppressResponder) readCorner(first, boxes) } }
        return boxes
    }

    private fun useLookedAtButton(first: Boolean): Button =
        Button.builder(Component.translatable("austrianpainter.area.use_looked_at")) {
            screen.lookedAtPos?.let {
                PaintArea.setCorner(first, it)
                syncFields()
                requestRescan()
            }
        }.width(120).build()

    private fun axisKey(axis: Int): String = when (axis) {
        0 -> "austrianpainter.area.axis_x"
        1 -> "austrianpainter.area.axis_y"
        else -> "austrianpainter.area.axis_z"
    }

    /** Blank or half-typed coordinates just leave the stored corner alone. */
    private fun readCorner(first: Boolean, fields: Array<EditBox>) {
        val values = fields.map { it.value.trim().toIntOrNull() }
        if (values.any { it == null }) return

        PaintArea.setCorner(first, BlockPos(values[0]!!, values[1]!!, values[2]!!))
        requestRescan()
    }

    private fun syncFields() {
        suppressResponder = true
        writeCorner(fields1, PaintArea.corner1)
        writeCorner(fields2, PaintArea.corner2)
        suppressResponder = false
    }

    private fun writeCorner(fields: Array<EditBox>, pos: BlockPos?) {
        val text = listOf(pos?.x, pos?.y, pos?.z)
        for (axis in 0 until 3) {
            fields[axis].value = text[axis]?.toString() ?: ""
        }
    }

    // ------------------------------------------------------------------ layout

    override fun doLayout(area: ScreenRectangle) {
        val x = area.left() + MARGIN
        val contentWidth = area.width() - MARGIN * 2
        var y = area.top() + GAP

        y = placeCornerRow(x, y, cornerOneLabel, fields1, useLookedAt1)
        y = placeCornerRow(x, y, cornerTwoLabel, fields2, useLookedAt2)
        y += GAP

        for (line in listOf(sizeLine, sourceLine, donorLine)) {
            line.setRectangle(contentWidth, TextLineWidget.HEIGHT, x, y)
            y += TextLineWidget.HEIGHT
        }
        y += GAP

        y = placeButtons(x, y, donorButton, rescanButton, clearSelectionButton)
        y = placeButtons(x, y, replaceButton, replaceRandomButton, rerollButton)
        y = placeButtons(x, y, clearPaintedButton)
        y += GAP

        val listHeight = (area.bottom() - y - GAP).coerceAtLeast(ROW_HEIGHT)
        list.setRectangle(contentWidth, listHeight, x, y)
    }

    private fun placeCornerRow(
        startX: Int,
        y: Int,
        label: TextLineWidget,
        fields: Array<EditBox>,
        button: Button,
    ): Int {
        label.setRectangle(LABEL_WIDTH, TextLineWidget.HEIGHT, startX, y + 5)
        var x = startX + LABEL_WIDTH
        for (field in fields) {
            field.setRectangle(FIELD_WIDTH, 18, x, y)
            x += FIELD_WIDTH + GAP
        }
        button.setRectangle(button.width, BUTTON_HEIGHT, x + 2, y - 1)
        return y + 22
    }

    private fun placeButtons(startX: Int, y: Int, vararg buttons: Button): Int {
        var x = startX
        for (button in buttons) {
            button.setRectangle(button.width, BUTTON_HEIGHT, x, y)
            x += button.width + GAP
        }
        return y + BUTTON_HEIGHT + GAP
    }

    // ------------------------------------------------------------------ scanning

    private fun requestRescan() {
        rescanIn = RESCAN_DELAY_TICKS
    }

    override fun tick() {
        if (rescanIn < 0) return
        rescanIn--
        if (rescanIn < 0) rescan()
    }

    override fun refresh() {
        syncFields()
        rescan()
    }

    private fun rescan() {
        rescanIn = -1
        list.rows = logic.scan()
        refreshButtons()
    }

    private fun refreshButtons() {
        val usable = PaintArea.complete && !logic.tooBig() && PaintArea.hasSource
        replaceButton.active = usable && PaintArea.donor != null
        replaceRandomButton.active = usable && !logic.palette().isEmpty()
        rerollButton.active = PaintArea.lastApplied.isNotEmpty() && !logic.palette().isEmpty()
        clearPaintedButton.active = PaintArea.complete && !logic.tooBig()

        sizeLine.message = logic.sizeText()
        sizeLine.color = if (logic.tooBig()) RED else GREY
        sourceLine.message = logic.sourceText()
        donorLine.message = logic.donorText()
        list.emptyColor = if (logic.tooBig()) RED else RowListWidget.EMPTY_TEXT
    }
}
