package com.maxisch.client.gui

import com.maxisch.client.AreaScan
import com.maxisch.client.PaintArea
import com.maxisch.client.PaintSelection
import com.maxisch.paint.PaintStorage
import com.maxisch.paint.PalettePreset
import com.maxisch.paint.PresetStores
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.util.RandomSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block

/**
 * Box selection workflow: set two corners, see what is actually inside the box, then repaint one of
 * those block types. The replace is flattened to positions straight away, so it behaves exactly like
 * a very large brush stroke and nothing new has to be persisted.
 */
class AreaReplaceScreen(private val parent: Screen?) :
    Screen(Component.translatable("austrianpainter.area.title")) {

    private companion object {
        const val MARGIN = 8
        const val HEADER = 92
        const val FOOTER = 56
        const val ROW_HEIGHT = 14

        const val LABEL_WIDTH = 56
        const val FIELD_WIDTH = 46
        const val FIELD_GAP = 4

        /** Corners update live, but a scan of a large box should not run on every keystroke. */
        const val RESCAN_DELAY_TICKS = 8
    }

    private sealed interface Row {
        /** Synthetic first row: every non-air block in the box. */
        data class All(val count: Int) : Row
        data class Type(val block: Block, val count: Int) : Row
    }

    private var rows: List<Row> = emptyList()
    private var scroll = 0

    private var rescanIn = -1

    /** Captured when the screen opens, because the crosshair is frozen while a screen is up. */
    private var lookedAtPos: BlockPos? = null

    private val fields1 = arrayOfNulls<EditBox>(3)
    private val fields2 = arrayOfNulls<EditBox>(3)
    private var suppressResponder = false

    private lateinit var replaceButton: Button
    private lateinit var rerollButton: Button
    private lateinit var clearAreaButton: Button

    private val listX get() = MARGIN
    private val listY get() = HEADER
    private val listWidth get() = width - MARGIN * 2
    private val listRows get() = ((height - HEADER - FOOTER) / ROW_HEIGHT).coerceAtLeast(1)

    override fun init() {
        lookedAtPos = PaintSelection.lookedAtPos()

        buildCornerRow(first = true, y = 24, fields = fields1)
        buildCornerRow(first = false, y = 46, fields = fields2)

        var x = MARGIN
        addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.area.edit_palette")) {
                minecraft.setScreenAndShow(PaletteScreen(this))
            }.bounds(x, height - FOOTER + 6, 110, 20).build(),
        )
        x += 114

        addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.area.rescan")) { rescan() }
                .bounds(x, height - FOOTER + 6, 80, 20).build(),
        )
        x += 84

        addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.area.clear_selection")) {
                PaintArea.clearCorners()
                PaintArea.source = null
                PaintArea.sourceAll = false
                syncFields()
                rescan()
            }.bounds(x, height - FOOTER + 6, 120, 20).build(),
        )

        replaceButton = addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.area.replace")) { replace() }
                .bounds(MARGIN, height - 26, 100, 20).build(),
        )

        rerollButton = addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.area.reroll")) { reroll() }
                .bounds(MARGIN + 104, height - 26, 80, 20).build(),
        )

        clearAreaButton = addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.area.clear_painted")) { clearPainted() }
                .bounds(MARGIN + 188, height - 26, 150, 20).build(),
        )

        addRenderableWidget(
            Button.builder(Component.translatable("gui.done")) { onClose() }
                .bounds(width - MARGIN - 100, height - 26, 100, 20).build(),
        )

        syncFields()
        rescan()
    }

    override fun onClose() {
        val target = parent
        if (target == null) super.onClose() else minecraft.setScreenAndShow(target)
    }

    // ------------------------------------------------------------------ corner fields

    private fun buildCornerRow(first: Boolean, y: Int, fields: Array<EditBox?>) {
        var x = MARGIN + LABEL_WIDTH
        for (axis in 0 until 3) {
            val box = addRenderableWidget(
                EditBox(font, x, y, FIELD_WIDTH, 18, Component.translatable(axisKey(axis))),
            )
            box.setMaxLength(8)
            box.setHint(Component.translatable(axisKey(axis)))
            box.setResponder { if (!suppressResponder) readCorner(first, fields) }
            fields[axis] = box
            x += FIELD_WIDTH + FIELD_GAP
        }

        addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.area.use_looked_at")) {
                lookedAtPos?.let {
                    PaintArea.setCorner(first, it)
                    syncFields()
                    requestRescan()
                }
            }.bounds(x + 2, y - 1, 120, 20).build(),
        )
    }

    private fun axisKey(axis: Int): String = when (axis) {
        0 -> "austrianpainter.area.axis_x"
        1 -> "austrianpainter.area.axis_y"
        else -> "austrianpainter.area.axis_z"
    }

    /** Blank or half-typed coordinates just leave the stored corner alone. */
    private fun readCorner(first: Boolean, fields: Array<EditBox?>) {
        val values = fields.map { it?.value?.trim()?.toIntOrNull() }
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

    private fun writeCorner(fields: Array<EditBox?>, pos: BlockPos?) {
        val text = listOf(pos?.x, pos?.y, pos?.z)
        for (axis in 0 until 3) {
            fields[axis]?.value = text[axis]?.toString() ?: ""
        }
    }

    // ------------------------------------------------------------------ scanning

    private fun requestRescan() {
        rescanIn = RESCAN_DELAY_TICKS
    }

    override fun tick() {
        super.tick()
        if (rescanIn < 0) return
        rescanIn--
        if (rescanIn < 0) rescan()
    }

    private fun rescan() {
        rescanIn = -1

        val level = minecraft.level
        rows = if (level == null || !PaintArea.complete || tooBig()) {
            emptyList()
        } else {
            val histogram = AreaScan.histogram(level)
            buildList {
                add(Row.All(histogram.values.sum()))
                histogram.forEach { (block, count) -> add(Row.Type(block, count)) }
            }
        }

        // Drop a source that is no longer in the box so the button state cannot lie.
        if (rows.none { it is Row.Type && it.block == PaintArea.source }) PaintArea.source = null

        scroll = scroll.coerceIn(0, (rows.size - listRows).coerceAtLeast(0))
        refreshButtons()
    }

    private fun tooBig(): Boolean = PaintArea.volume() > PaintArea.MAX_VOLUME

    private fun palette(): PalettePreset = PresetStores.palettes.active

    private fun refreshButtons() {
        val usable = PaintArea.complete && !tooBig()
        replaceButton.active = usable && PaintArea.hasSource && !palette().isEmpty()
        rerollButton.active = PaintArea.lastApplied.isNotEmpty() && !palette().isEmpty()
        clearAreaButton.active = usable
    }

    // ------------------------------------------------------------------ actions

    private fun replace() {
        val level = minecraft.level ?: return
        if (!PaintArea.hasSource) return

        val positions = if (PaintArea.sourceAll) {
            AreaScan.allPositions(level)
        } else {
            AreaScan.positionsOf(level, PaintArea.source ?: return)
        }

        PaintArea.lastApplied = positions
        val painted = draw(positions)
        tell(
            Component.translatable(
                "austrianpainter.area.replaced",
                painted,
                sourceName(),
                PresetStores.palettes.activeName,
            ),
        )
        rescan()
    }

    /** Redraws the previous apply. The same positions, a fresh roll of the same palette. */
    private fun reroll() {
        val positions = PaintArea.lastApplied
        if (positions.isEmpty()) return

        val painted = draw(positions)
        tell(Component.translatable("austrianpainter.area.rerolled", painted))
        refreshButtons()
    }

    private fun draw(positions: List<BlockPos>): Int {
        val picker = palette().picker() ?: return 0
        val random = RandomSource.create()
        return PaintStorage.paintPositions(positions) { picker.next(random) }
    }

    private fun clearPainted() {
        if (!PaintArea.complete || tooBig()) return

        val cleared = PaintStorage.unpaintPositions(PaintArea.positions().toList())
        tell(Component.translatable("austrianpainter.area.cleared", cleared))
    }

    private fun tell(message: Component) {
        minecraft.player?.sendSystemMessage(message)
    }

    // ------------------------------------------------------------------ input

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        if (super.mouseClicked(event, doubled)) return true

        when (val row = rowAt(event.x, event.y)) {
            is Row.All -> {
                PaintArea.sourceAll = true
                PaintArea.source = null
            }

            is Row.Type -> {
                PaintArea.sourceAll = false
                PaintArea.source = row.block
            }

            null -> return false
        }
        refreshButtons()
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val delta = if (scrollY > 0) -1 else if (scrollY < 0) 1 else 0
        if (delta == 0) return false
        scroll = (scroll + delta).coerceIn(0, (rows.size - listRows).coerceAtLeast(0))
        return true
    }

    private fun rowAt(mouseX: Double, mouseY: Double): Row? {
        if (mouseX < listX || mouseX > listX + listWidth || mouseY < listY) return null
        val index = ((mouseY - listY) / ROW_HEIGHT).toInt()
        if (index !in 0 until listRows) return null
        return rows.getOrNull(scroll + index)
    }

    private fun isSelected(row: Row): Boolean = when (row) {
        is Row.All -> PaintArea.sourceAll
        is Row.Type -> !PaintArea.sourceAll && row.block == PaintArea.source
    }

    // ------------------------------------------------------------------ render

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        graphics.centeredText(font, title, width / 2, 8, 0xFFFFFFFF.toInt())
        graphics.text(font, Component.translatable("austrianpainter.area.corner_one"), MARGIN, 29, 0xFFA0A0A0.toInt())
        graphics.text(font, Component.translatable("austrianpainter.area.corner_two"), MARGIN, 51, 0xFFA0A0A0.toInt())

        graphics.text(font, sizeLine(), MARGIN, 70, if (tooBig()) 0xFFFF5555.toInt() else 0xFFA0A0A0.toInt())
        graphics.text(font, donorLine(), MARGIN, 80, 0xFFFFFF55.toInt())

        drawRows(graphics, mouseX, mouseY)
    }

    private fun sizeLine(): Component {
        val min = PaintArea.min()
        val max = PaintArea.max()
        if (min == null || max == null) return Component.translatable("austrianpainter.area.incomplete")
        if (tooBig()) return Component.translatable("austrianpainter.area.too_big", PaintArea.MAX_VOLUME)

        return Component.translatable(
            "austrianpainter.area.size",
            max.x - min.x + 1,
            max.y - min.y + 1,
            max.z - min.z + 1,
            PaintArea.volume(),
        )
    }

    private fun sourceName(): Component =
        if (PaintArea.sourceAll) {
            Component.translatable("austrianpainter.area.everything")
        } else {
            PaintArea.source?.name ?: Component.translatable("austrianpainter.area.any_source")
        }

    private fun donorLine(): Component {
        if (!PaintArea.hasSource) return Component.translatable("austrianpainter.area.no_source")
        if (palette().isEmpty()) return Component.translatable("austrianpainter.area.palette_empty")

        return Component.translatable(
            "austrianpainter.area.pending_palette",
            sourceName(),
            PresetStores.palettes.activeName,
            palette().size,
        )
    }

    private fun drawRows(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val listHeight = listRows * ROW_HEIGHT
        graphics.fill(listX - 2, listY - 2, listX + listWidth + 2, listY + listHeight + 2, 0x60000000)

        if (rows.isEmpty()) {
            graphics.text(font, emptyLine(), listX + 2, listY + 3, 0xFF808080.toInt())
            return
        }

        graphics.enableScissor(listX, listY, listX + listWidth, listY + listHeight)
        for (index in 0 until listRows) {
            val row = rows.getOrNull(scroll + index) ?: break
            val y = listY + index * ROW_HEIGHT

            if (isSelected(row)) {
                graphics.fill(listX, y, listX + listWidth, y + ROW_HEIGHT, 0x8055FF55.toInt())
            } else if (mouseX in listX..(listX + listWidth) && mouseY in y until y + ROW_HEIGHT) {
                graphics.fill(listX, y, listX + listWidth, y + ROW_HEIGHT, 0x60FFFFFF)
            }

            val label = when (row) {
                is Row.All -> Component.translatable("austrianpainter.area.row_all", row.count)
                is Row.Type -> {
                    val stack = ItemStack(row.block)
                    if (!stack.isEmpty) graphics.item(stack, listX + 2, y - 1)
                    Component.translatable("austrianpainter.area.row", row.block.name, row.count)
                }
            }
            graphics.text(font, label, listX + 22, y + 3, 0xFFFFFFFF.toInt())
        }
        graphics.disableScissor()

        if (rowAt(mouseX.toDouble(), mouseY.toDouble()) != null) {
            graphics.setTooltipForNextFrame(
                Component.translatable("austrianpainter.area.pick_source"), mouseX, mouseY,
            )
        }
    }

    private fun emptyLine(): Component = when {
        !PaintArea.complete -> Component.translatable("austrianpainter.area.incomplete")
        tooBig() -> Component.translatable("austrianpainter.area.too_big", PaintArea.MAX_VOLUME)
        else -> Component.translatable("austrianpainter.area.scan_empty")
    }

    override fun isPauseScreen(): Boolean = false
}
