package com.maxisch.client.gui.tab

import com.maxisch.client.keybind.KeyHints
import com.maxisch.client.gui.screen.BlockPickerScreen
import com.maxisch.client.gui.screen.BlockSearch
import com.maxisch.client.gui.screen.PainterScreen
import com.maxisch.client.gui.widget.ActButtonWidget
import com.maxisch.client.gui.widget.RowContent
import com.maxisch.client.gui.widget.RowListWidget
import com.maxisch.client.gui.widget.CardWidget
import com.maxisch.client.gui.widget.CoordinatePadWidget
import com.maxisch.client.gui.widget.TextLineWidget
import com.maxisch.client.render.overlay.BlockHighlight
import com.maxisch.paint.rule.AreaSelector
import com.maxisch.paint.rule.PaintFilter
import com.maxisch.paint.PaintStorage
import com.maxisch.paint.session.AreaShape
import com.maxisch.paint.session.PaintArea
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import com.maxisch.client.gui.widget.ApEditBox
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
        const val BUTTON_HEIGHT = 16
        const val GAP = 4

        const val LABEL_WIDTH = 56

        /** Inset of the corner rows inside [CoordinatePadWidget]. */
        const val PAD_INNER = 6
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

    /** Added before the rows it frames - render order is add() order, and it draws underneath them. */
    private val pad = add(CoordinatePadWidget { if (PaintArea.complete) PaintArea.volume() else null })

    private val fillCard = add(CardWidget(Component.translatable("austrianpainter.card.fill_from")))
    private val actionsCard = add(CardWidget(Component.translatable("austrianpainter.card.actions")))
    private val dangerCard = add(CardWidget(Component.translatable("austrianpainter.card.danger")))

    private val listCard = add(
        CardWidget(Component.translatable("austrianpainter.card.scan")) {
            Component.translatable("austrianpainter.card.scan.count", PaintArea.selected.size, list.rows.size)
        },
    )

    private val cornerOneLabel = add(TextLineWidget(0, 0, LABEL_WIDTH, GREY)).also {
        it.message = Component.translatable("austrianpainter.area.corner_one")
    }
    private val cornerTwoLabel = add(TextLineWidget(0, 0, LABEL_WIDTH, GREY)).also {
        it.message = Component.translatable("austrianpainter.area.corner_two")
    }

    private val fields1 = buildFields(first = true)
    private val fields2 = buildFields(first = false)

    private val sizeLine = add(TextLineWidget(0, 0, 0, GREY))
    private val mappingLine = add(TextLineWidget(0, 0, 0, YELLOW))

    // ------------------------------------------------------------------ buttons

    private val donorButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.pick_donor")) {
            Minecraft.getInstance().setScreenAndShow(
                BlockPickerScreen(screen) { logic.assignDonor(it) },
            )
        }.width(100).build(),
    )

    private val paletteButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.area.use_palette")) {
            logic.assignPalette()
            refreshButtons()
        }.width(100).build(),
    )

    private val selectAllButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.area.select_all")) {
            // Block types only: "Everything" and "Unchanged" overlap them, and a rule on all three
            // at once would leave two of them matching nothing once precedence is resolved.
            list.rows.forEach { if (it.selector is AreaSelector.Type) PaintArea.selected.add(it.selector) }
            refreshButtons()
        }.width(90).build(),
    )

    private val replaceButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.area.replace")) {
            logic.replace()
            rescan()
        }.width(100).variant(ActButtonWidget.Variant.PRIMARY).build(),
    )

    private val replaceRandomButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.area.replace_random")) {
            logic.replaceRandom()
            rescan()
        }.width(120).variant(ActButtonWidget.Variant.PRIMARY).build(),
    )

    private val rerollButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.area.reroll")) {
            logic.reroll()
            refreshButtons()
        }.width(75).build(),
    )

    private val reapplyButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.area.reapply_last")) {
            logic.reapplyLast()
            rescan()
        }.width(105).build(),
    )

    private val saveRulesetButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.area.save_ruleset")) {
            logic.saveRuleset()
            refreshButtons()
        }.width(100).build(),
    )

    private val loadRulesetButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.area.load_ruleset")) {
            logic.loadRuleset()
            refreshButtons()
        }.width(100).build(),
    )

    private val clearMappingButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.area.clear_mapping")) {
            logic.clearRules()
            refreshButtons()
        }.width(95).variant(ActButtonWidget.Variant.DANGER).build(),
    )

    private val unpaintSelectedButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.area.unpaint_selected")) {
            logic.clearSelectedPaint()
        }.width(120).variant(ActButtonWidget.Variant.DANGER).build(),
    )

    private val clearPaintedButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.area.clear_painted")) { logic.clearPainted() }
            .width(105).variant(ActButtonWidget.Variant.DANGER).build(),
    )

    private val clearSelectionButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.area.clear_selection")) {
            PaintArea.clearSelection()
            syncFields()
            rescan()
        }
            .width(105)
            .tooltip(KeyHints.clearAreaTooltip())
            .build(),
    )

    private val selectRoomButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.area.select_room")) { selectRoom() }
            .width(105).build(),
    )

    private val shapeButton = add(
        ActButtonWidget.builder(shapeLabel()) { cycleShape() }.width(120).build(),
    )

    private val previewButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.area.preview")) {
            logic.preview()
            refreshButtons()
        }.width(90).build(),
    )

    private val clearPreviewButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.area.clear_preview")) {
            logic.clearPreview()
            refreshButtons()
        }.width(110).build(),
    )

    private val rescanButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.area.rescan")) { rescan() }.width(70).build(),
    )

    private val searchBox = add(
        ApEditBox(font, 0, 0, 100, 18, Component.translatable("austrianpainter.search")).apply {
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
                RowContent.label(graphics, x + 22, y, ROW_HEIGHT, x + width, text = label)
            }
        },
    )

    init {
        searchBox.setResponder { applyFilter() }
    }

    // ------------------------------------------------------------------ room and shape

    /** The scan already knows where the room is; flying to two opposite corners is busywork. */
    private fun selectRoom() {
        val corners = PaintStorage.currentRoomCorners()
        if (corners == null) {
            screen.status(Component.translatable("austrianpainter.area.no_room"))
            return
        }

        PaintArea.setCorner(first = true, pos = corners.first)
        PaintArea.setCorner(first = false, pos = corners.second)
        syncFields()
        rescan()
        screen.status(
            Component.translatable(
                "austrianpainter.area.room_selected",
                PaintStorage.scope?.key ?: "?",
                PaintArea.volume(),
            ),
        )
    }

    private fun cycleShape() {
        val shapes = AreaShape.entries
        PaintArea.shape = shapes[(PaintArea.shape.ordinal + 1) % shapes.size]
        // The counts in the list are shape-filtered, so they are wrong until this rescans.
        rescan()
    }

    private fun shapeLabel(): Component = Component.translatable(
        "austrianpainter.area.shape",
        Component.translatable("austrianpainter.area.shape.${PaintArea.shape.key}"),
    )

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

    private fun buildFields(first: Boolean): Array<ApEditBox> {
        val boxes = Array(3) { axis ->
            val box = ApEditBox(font, 0, 0, FIELD_WIDTH, 18, Component.translatable(axisKey(axis)))
            box.setMaxLength(8)
            box.setHint(Component.translatable(axisKey(axis)))
            add(box)
        }
        // The responder is wired after construction so filling the array cannot re-enter readCorner.
        boxes.forEach { box -> box.setResponder { if (!suppressResponder) readCorner(first, boxes) } }
        return boxes
    }

    private fun axisKey(axis: Int): String = when (axis) {
        0 -> "austrianpainter.area.axis_x"
        1 -> "austrianpainter.area.axis_y"
        else -> "austrianpainter.area.axis_z"
    }

    /** Blank or half-typed coordinates just leave the stored corner alone. */
    private fun readCorner(first: Boolean, fields: Array<ApEditBox>) {
        val values = fields.map { it.value.trim().toIntOrNull() }
        if (values.any { it == null }) return

        // None of the three is null past the check above - Kotlin just can't see it through the list.
        PaintArea.setCorner(first, BlockPos(values[0]!!, values[1]!!, values[2]!!))
        requestRescan()
    }

    private fun syncFields() {
        suppressResponder = true
        writeCorner(fields1, PaintArea.corner1)
        writeCorner(fields2, PaintArea.corner2)
        suppressResponder = false
    }

    private fun writeCorner(fields: Array<ApEditBox>, pos: BlockPos?) {
        val text = listOf(pos?.x, pos?.y, pos?.z)
        for (axis in 0 until 3) {
            fields[axis].value = text[axis]?.toString() ?: ""
        }
    }

    // ------------------------------------------------------------------ layout

    override fun layout(area: ScreenRectangle) {
        val x = area.left() + MARGIN
        val contentWidth = area.width() - MARGIN * 2
        val top = area.top() + GAP

        // Two columns, as the shell draws this tool: what defines and fills the box on the left,
        // what is inside it on the right. The left column is the narrower of the two - its controls
        // are fixed-width, while the list wants every pixel it can get for block names.
        val leftWidth = (contentWidth - GAP) * 2 / 5
        val rightWidth = contentWidth - leftWidth - GAP
        val rightX = x + leftWidth + GAP

        // -------------------------------------------------- selection pad
        var y = top
        y = placeCornerRow(x + PAD_INNER, y + PAD_INNER, cornerOneLabel, fields1)
        y = placeCornerRow(x + PAD_INNER, y, cornerTwoLabel, fields2)
        y += CoordinatePadWidget.VOLUME_HEIGHT
        pad.setRectangle(leftWidth, y - top, x, top)

        // -------------------------------------------------- select room - a third way to define the
        // box, same zone as the corner fields above rather than an "action" on the box's contents.
        y += GAP
        selectRoomButton.setRectangle(leftWidth, BUTTON_HEIGHT, x, y)
        y += BUTTON_HEIGHT

        // -------------------------------------------------- fill-from card
        val fillTop = y + GAP
        y = fillTop + CardWidget.HEADER_HEIGHT
        y = gridButtons(x + CardWidget.PAD, y, leftWidth - CardWidget.PAD * 2, 1, donorButton, paletteButton, shapeButton)
        y += CardWidget.PAD - GAP
        fillCard.setRectangle(leftWidth, y - fillTop, x, fillTop)

        // -------------------------------------------------- actions card - commit pair first (the
        // two verbs used on every pass through this tab), then the rest as a 2-column grid.
        val actionsTop = y + GAP
        y = actionsTop + CardWidget.HEADER_HEIGHT
        val actionsInner = leftWidth - CardWidget.PAD * 2
        y = gridButtons(x + CardWidget.PAD, y, actionsInner, 2, replaceButton, replaceRandomButton)
        y += GAP
        y = gridButtons(
            x + CardWidget.PAD, y, actionsInner, 2,
            rerollButton, selectAllButton,
            previewButton, clearPreviewButton,
            clearSelectionButton, reapplyButton,
            saveRulesetButton, loadRulesetButton,
        )
        y += CardWidget.PAD - GAP
        actionsCard.setRectangle(leftWidth, y - actionsTop, x, actionsTop)

        // -------------------------------------------------- danger zone card - the three actions
        // that discard work, stacked full-width so they read as a warning list, not a compact grid.
        val dangerTop = y + GAP
        y = dangerTop + CardWidget.HEADER_HEIGHT
        y = gridButtons(
            x + CardWidget.PAD, y, leftWidth - CardWidget.PAD * 2, 1,
            clearMappingButton, unpaintSelectedButton, clearPaintedButton,
        )
        y += CardWidget.PAD - GAP
        dangerCard.setRectangle(leftWidth, y - dangerTop, x, dangerTop)

        y += GAP
        for (line in listOf(sizeLine, mappingLine)) {
            line.setRectangle(leftWidth, TextLineWidget.HEIGHT, x, y)
            y += TextLineWidget.HEIGHT
        }

        // -------------------------------------------------- scan column
        rescanButton.setRectangle(rescanButton.width, BUTTON_HEIGHT, rightX, top)
        val searchX = rightX + rescanButton.width + GAP
        searchBox.setRectangle((rightX + rightWidth - searchX).coerceAtLeast(20), 18, searchX, top + 1)

        val listTop = top + BUTTON_HEIGHT + GAP
        val listHeight = (area.bottom() - listTop - GAP).coerceAtLeast(ROW_HEIGHT)
        listCard.setRectangle(rightWidth, listHeight, rightX, listTop)
        list.setRectangle(
            rightWidth - CardWidget.PAD * 2,
            (listHeight - CardWidget.HEADER_HEIGHT - CardWidget.PAD).coerceAtLeast(ROW_HEIGHT),
            rightX + CardWidget.PAD,
            listTop + CardWidget.HEADER_HEIGHT,
        )
    }

    /**
     * Lays buttons into a fixed grid, [columns] wide, every button sharing one equal column width so
     * rows visibly line up instead of packing ragged at each button's own label width.
     */
    private fun gridButtons(startX: Int, startY: Int, width: Int, columns: Int, vararg buttons: ActButtonWidget): Int {
        val columnWidth = (width - (columns - 1) * GAP) / columns
        buttons.forEachIndexed { index, button ->
            val column = index % columns
            val row = index / columns
            button.setRectangle(
                columnWidth,
                BUTTON_HEIGHT,
                startX + column * (columnWidth + GAP),
                startY + row * (BUTTON_HEIGHT + GAP),
            )
        }
        val rows = (buttons.size + columns - 1) / columns
        return startY + rows * (BUTTON_HEIGHT + GAP)
    }

    private fun placeCornerRow(
        startX: Int,
        y: Int,
        label: TextLineWidget,
        fields: Array<ApEditBox>,
    ): Int {
        label.setRectangle(LABEL_WIDTH, TextLineWidget.HEIGHT, startX, y + 5)
        var x = startX + LABEL_WIDTH
        for (field in fields) {
            field.setRectangle(FIELD_WIDTH, 18, x, y)
            x += FIELD_WIDTH + GAP
        }
        return y + 22
    }

    private fun placeButtons(startX: Int, y: Int, vararg buttons: ActButtonWidget): Int {
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
        previewButton.active = boxReady && PaintArea.hasRules
        clearPreviewButton.active = BlockHighlight.hasPreview
        selectRoomButton.active = PaintStorage.scope?.isBoss == false
        shapeButton.message = shapeLabel()

        sizeLine.message = logic.sizeText()
        sizeLine.color = if (logic.tooBig()) RED else GREY
        mappingLine.message = logic.mappingText()
        list.emptyColor = if (logic.tooBig()) RED else RowListWidget.EMPTY_TEXT
    }
}
