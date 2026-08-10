package com.maxisch.client.gui.tab

import com.maxisch.client.gui.BlockPickerScreen
import com.maxisch.client.gui.BlockSearch
import com.maxisch.client.gui.PainterScreen
import com.maxisch.client.gui.widget.RowListWidget
import com.maxisch.client.gui.widget.TextLineWidget
import com.maxisch.paint.AreaSelector
import com.maxisch.paint.PaintFilter
import com.maxisch.paint.session.PaintArea
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/**
 * Box selection workflow: set two corners, see what is actually inside the box, aim a rule at each
 * block type worth repainting, then apply the lot in one go. The replace is flattened to positions
 * straight away, so it behaves exactly like a very large brush stroke and nothing new has to be
 * persisted - the rules themselves are only an authoring tool, saved as a ruleset preset.
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

    /** The whole scan; [list] shows whatever survives the search box. */
    private var scanned: List<AreaScanLogic.Row> = emptyList()

    /** Where a shift-click measures its range from; an index into the filtered rows. */
    private var anchor = -1

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
    private val mappingLine = add(TextLineWidget(0, 0, 0, YELLOW))

    // ------------------------------------------------------------------ buttons

    private val donorButton = add(
        Button.builder(Component.translatable("austrianpainter.pick_donor")) {
            Minecraft.getInstance().setScreenAndShow(
                BlockPickerScreen(screen) { logic.assignDonor(it) },
            )
        }.width(100).build(),
    )

    private val paletteButton = add(
        Button.builder(Component.translatable("austrianpainter.area.use_palette")) {
            logic.assignPalette()
            refreshButtons()
        }.width(100).build(),
    )

    private val selectAllButton = add(
        Button.builder(Component.translatable("austrianpainter.area.select_all")) {
            // Block types only: "Everything" and "Unchanged" overlap them, and a rule on all three
            // at once would leave two of them matching nothing once precedence is resolved.
            list.rows.forEach { if (it.selector is AreaSelector.Type) PaintArea.selected.add(it.selector) }
            refreshButtons()
        }.width(90).build(),
    )

    private val replaceButton = add(
        Button.builder(Component.translatable("austrianpainter.area.replace")) {
            logic.replace()
            rescan()
        }.width(100).build(),
    )

    private val replaceRandomButton = add(
        Button.builder(Component.translatable("austrianpainter.area.replace_random")) {
            logic.replaceRandom()
            rescan()
        }.width(120).build(),
    )

    private val rerollButton = add(
        Button.builder(Component.translatable("austrianpainter.area.reroll")) {
            logic.reroll()
            refreshButtons()
        }.width(75).build(),
    )

    private val reapplyButton = add(
        Button.builder(Component.translatable("austrianpainter.area.reapply_last")) {
            logic.reapplyLast()
            rescan()
        }.width(105).build(),
    )

    private val saveRulesetButton = add(
        Button.builder(Component.translatable("austrianpainter.area.save_ruleset")) {
            logic.saveRuleset()
            refreshButtons()
        }.width(100).build(),
    )

    private val loadRulesetButton = add(
        Button.builder(Component.translatable("austrianpainter.area.load_ruleset")) {
            logic.loadRuleset()
            refreshButtons()
        }.width(100).build(),
    )

    private val clearMappingButton = add(
        Button.builder(Component.translatable("austrianpainter.area.clear_mapping")) {
            logic.clearRules()
            refreshButtons()
        }.width(95).build(),
    )

    private val unpaintSelectedButton = add(
        Button.builder(Component.translatable("austrianpainter.area.unpaint_selected")) {
            logic.clearSelectedPaint()
        }.width(120).build(),
    )

    private val clearPaintedButton = add(
        Button.builder(Component.translatable("austrianpainter.area.clear_painted")) { logic.clearPainted() }
            .width(105).build(),
    )

    private val clearSelectionButton = add(
        Button.builder(Component.translatable("austrianpainter.area.clear_selection")) {
            PaintArea.clearSelection()
            syncFields()
            rescan()
        }.width(105).build(),
    )

    private val rescanButton = add(
        Button.builder(Component.translatable("austrianpainter.area.rescan")) { rescan() }.width(70).build(),
    )

    private val searchBox = add(
        EditBox(font, 0, 0, 100, 18, Component.translatable("austrianpainter.search")).apply {
            setHint(Component.translatable("austrianpainter.search"))
        },
    )

    private val list = add(
        RowListWidget<AreaScanLogic.Row>(0, 0, 0, 0, ROW_HEIGHT).apply {
            emptyMessage = { logic.emptyText() }
            hoverTooltip = { Component.translatable("austrianpainter.area.pick_row") }
            isSelected = { it.selector in PaintArea.selected }
            onRowSelect = { row, index, ctrl, shift -> select(row, index, ctrl, shift) }
            drawRow = { graphics, row, x, y, _ ->
                val selector = row.selector
                if (selector is AreaSelector.Type) {
                    val stack = ItemStack(selector.block)
                    if (!stack.isEmpty) graphics.item(stack, x + 2, y - 1)
                }

                val target = PaintArea.rules[selector]
                val label = if (target == null) {
                    Component.translatable(
                        "austrianpainter.area.row",
                        logic.selectorName(selector),
                        row.count,
                    )
                } else {
                    Component.translatable(
                        "austrianpainter.area.row_mapped",
                        logic.selectorName(selector),
                        row.count,
                        logic.targetName(target),
                    )
                }
                graphics.text(font, label, x + 22, y + 3, 0xFFFFFFFF.toInt())
            }
        },
    )

    init {
        searchBox.setResponder { applyFilter() }
    }

    // ------------------------------------------------------------------ selection

    /**
     * Plain click replaces the selection, ctrl-click toggles one row, shift-click takes the range
     * back to whichever row was last clicked plainly.
     */
    private fun select(row: AreaScanLogic.Row, index: Int, ctrl: Boolean, shift: Boolean) {
        val rows = list.rows
        when {
            shift && anchor in rows.indices -> {
                PaintArea.selected.clear()
                val range = if (anchor <= index) anchor..index else index..anchor
                range.forEach { PaintArea.selected.add(rows[it].selector) }
            }

            ctrl -> {
                if (!PaintArea.selected.remove(row.selector)) PaintArea.selected.add(row.selector)
                anchor = index
            }

            else -> {
                PaintArea.selected.clear()
                PaintArea.selected.add(row.selector)
                anchor = index
            }
        }
        refreshButtons()
    }

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

        for (line in listOf(sizeLine, mappingLine)) {
            line.setRectangle(contentWidth, TextLineWidget.HEIGHT, x, y)
            y += TextLineWidget.HEIGHT
        }
        y += GAP

        y = placeButtons(x, y, donorButton, paletteButton, selectAllButton)
        y = placeButtons(x, y, replaceButton, replaceRandomButton, rerollButton)
        y = placeButtons(x, y, reapplyButton, saveRulesetButton, loadRulesetButton)
        y = placeButtons(x, y, clearMappingButton, unpaintSelectedButton, clearPaintedButton)

        // The search box shares the last row with the two selection buttons: it belongs directly
        // above the list it filters, and a row of its own would cost the list another 24 pixels.
        rescanButton.setRectangle(rescanButton.width, BUTTON_HEIGHT, x, y)
        clearSelectionButton.setRectangle(
            clearSelectionButton.width,
            BUTTON_HEIGHT,
            x + rescanButton.width + GAP,
            y,
        )
        val searchX = x + rescanButton.width + clearSelectionButton.width + GAP * 2
        searchBox.setRectangle((x + contentWidth - searchX).coerceAtLeast(20), 18, searchX, y + 1)
        y += BUTTON_HEIGHT + GAP * 2

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
        scanned = logic.scan()
        applyFilter()
    }

    /**
     * The synthetic rows drop out while a query is typed, so "Select all" over a filter means the
     * types that matched rather than quietly re-adding everything.
     */
    private fun applyFilter() {
        val query = BlockSearch.normalize(searchBox.value)
        list.rows = if (query.isEmpty()) {
            scanned
        } else {
            scanned.filter { row ->
                val selector = row.selector
                if (selector !is AreaSelector.Type) {
                    false
                } else {
                    // Also match what the row currently renders as, so searching the donor finds
                    // the blocks already wearing it.
                    val paint = selector.paint
                    BlockSearch.matches(selector.block, query) ||
                        (paint is PaintFilter.PaintedAs && BlockSearch.matches(paint.donor, query))
                }
            }
        }
        anchor = -1
        refreshButtons()
    }

    private fun refreshButtons() {
        val boxReady = PaintArea.complete && !logic.tooBig()
        val hasSelection = PaintArea.selected.isNotEmpty()

        donorButton.active = hasSelection
        paletteButton.active = hasSelection && !logic.palette().isEmpty()
        selectAllButton.active = list.rows.isNotEmpty()

        replaceButton.active = boxReady && PaintArea.hasRules
        replaceRandomButton.active = boxReady && hasSelection && !logic.palette().isEmpty()
        rerollButton.active = PaintArea.lastPaletteApplied.isNotEmpty()

        reapplyButton.active = boxReady && PaintArea.lastRules.isNotEmpty()
        saveRulesetButton.active = PaintArea.hasRules
        loadRulesetButton.active = !logic.ruleset().isEmpty()

        clearMappingButton.active = PaintArea.hasRules
        unpaintSelectedButton.active = boxReady && hasSelection
        clearPaintedButton.active = boxReady

        sizeLine.message = logic.sizeText()
        sizeLine.color = if (logic.tooBig()) RED else GREY
        mappingLine.message = logic.mappingText()
        list.emptyColor = if (logic.tooBig()) RED else RowListWidget.EMPTY_TEXT
    }
}
