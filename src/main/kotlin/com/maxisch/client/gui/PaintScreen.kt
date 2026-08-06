package com.maxisch.client.gui

import com.maxisch.client.PaintBrush
import com.maxisch.client.PaintSelection
import com.maxisch.paint.PaintStorage
import com.maxisch.paint.PresetStores
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block

/**
 * Rule manager. Picking blocks lives in [BlockPickerScreen] and options live in
 * [ApSettingsScreen]; this screen only shows what is currently painted and lets you undo it.
 *
 * Positions are never listed one by one - a preset can hold tens of thousands - so positional paint
 * is grouped by donor with a count. Removing a single position stays a world action: look at it and
 * press the erase key.
 */
class PaintScreen : Screen(Component.translatable("austrianpainter.screen.title")) {

    private companion object {
        const val MARGIN = 8
        const val HEADER = 62
        const val FOOTER = 80
        const val ROW_HEIGHT = 14
    }

    private sealed interface Row {
        data class Type(val source: Block, val target: Block) : Row
        data class Donor(val donor: Block, val count: Int) : Row
    }

    private var rows: List<Row> = emptyList()
    private var scroll = 0

    /** Captured when the screen opens, because the crosshair is frozen while a screen is up. */
    private var lookedAtPos: BlockPos? = null
    private var lookedAtBlock: Block? = null

    private lateinit var brushButton: Button
    private lateinit var applyButton: Button

    private val listX get() = MARGIN
    private val listY get() = HEADER
    private val listWidth get() = width - MARGIN * 2
    private val listRows get() = ((height - HEADER - FOOTER) / ROW_HEIGHT).coerceAtLeast(1)

    override fun init() {
        lookedAtPos = PaintSelection.lookedAtPos()
        lookedAtBlock = PaintSelection.lookedAtBlock()

        var x = MARGIN
        for (mode in PaintSelection.Mode.entries) {
            addRenderableWidget(
                Button.builder(Component.translatable("austrianpainter.mode.${mode.name.lowercase()}")) {
                    PaintSelection.mode = mode
                    refresh()
                }.bounds(x, height - FOOTER + 4, 74, 20).build(),
            )
            x += 76
        }

        addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.pick_donor")) {
                minecraft.setScreenAndShow(
                    BlockPickerScreen(this) { block ->
                        PaintSelection.target = block
                        PaintBrush.donor = block
                    },
                )
            }.bounds(x, height - FOOTER + 4, 110, 20).build(),
        )
        x += 112

        brushButton = addRenderableWidget(
            Button.builder(brushLabel()) {
                PaintBrush.enabled = !PaintBrush.enabled
                brushButton.message = brushLabel()
            }.bounds(x, height - FOOTER + 4, 100, 20).build(),
        )

        applyButton = addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.apply")) { apply() }
                .bounds(MARGIN, height - 52, 100, 20).build(),
        )

        addRenderableWidget(
            Button.builder(clearLabel()) {
                PaintStorage.clearCurrentScope()
                refresh()
            }.bounds(MARGIN + 104, height - 52, 130, 20).build(),
        )

        addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.area.open")) {
                minecraft.setScreenAndShow(AreaReplaceScreen(this))
            }.bounds(MARGIN + 238, height - 52, 130, 20).build(),
        )

        addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.settings")) {
                minecraft.setScreenAndShow(ApSettingsScreen.create(this))
            }.bounds(width - MARGIN - 204, height - 26, 100, 20).build(),
        )

        addRenderableWidget(
            Button.builder(Component.translatable("gui.done")) { onClose() }
                .bounds(width - MARGIN - 100, height - 26, 100, 20).build(),
        )

        refresh()
    }

    private fun brushLabel(): Component = Component.translatable(
        if (PaintBrush.enabled) "austrianpainter.brush.button_on" else "austrianpainter.brush.button_off",
        PaintBrush.radius,
    )

    /** In a dungeon room the list and the clear button are about that room, not the whole world. */
    private fun clearLabel(): Component {
        val room = PaintStorage.scope?.key
        return if (room == null) {
            Component.translatable("austrianpainter.clear_dimension")
        } else {
            Component.translatable("austrianpainter.clear_room", room)
        }
    }

    private fun refresh() {
        rows = buildList {
            PaintStorage.typeRules().forEach { (source, target) -> add(Row.Type(source, target)) }
            PaintStorage.positionsByDonor().forEach { (donor, count) -> add(Row.Donor(donor, count)) }
        }
        scroll = scroll.coerceIn(0, (rows.size - listRows).coerceAtLeast(0))
        applyButton.active = pendingIsValid()
    }

    // ------------------------------------------------------------------ apply

    private fun pendingIsValid(): Boolean {
        if (PaintSelection.target == null) return false
        return when (PaintSelection.mode) {
            PaintSelection.Mode.TYPE -> lookedAtBlock != null
            PaintSelection.Mode.POSITION -> lookedAtPos != null
        }
    }

    private fun apply() {
        val target = PaintSelection.target ?: return
        when (PaintSelection.mode) {
            PaintSelection.Mode.TYPE -> lookedAtBlock?.let { PaintStorage.setTypeRule(it, target) }
            PaintSelection.Mode.POSITION -> lookedAtPos?.let {
                PaintStorage.paintPositions(listOf(it), target)
            }
        }
        refresh()
    }

    // ------------------------------------------------------------------ input

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        if (super.mouseClicked(event, doubled)) return true

        when (val row = rowAt(event.x, event.y)) {
            is Row.Type -> PaintStorage.removeTypeRule(row.source)
            is Row.Donor -> PaintStorage.removeDonor(row.donor)
            null -> return false
        }
        refresh()
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

    // ------------------------------------------------------------------ render

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        graphics.centeredText(font, title, width / 2, 8, 0xFFFFFFFF.toInt())

        graphics.text(
            font,
            Component.translatable(
                "austrianpainter.active_presets",
                PresetStores.blocks.activeName,
                PresetStores.types.activeName,
            ),
            MARGIN, 22, 0xFFA0A0A0.toInt(),
        )
        graphics.text(font, contextLine(), MARGIN, 34, 0xFFA0A0A0.toInt())
        graphics.text(font, targetLine(), MARGIN, 46, 0xFFFFFF55.toInt())

        drawRows(graphics, mouseX, mouseY)
    }

    private fun contextLine(): Component = when (PaintSelection.mode) {
        PaintSelection.Mode.TYPE -> lookedAtBlock?.let {
            Component.translatable("austrianpainter.context.type", it.name)
        } ?: Component.translatable("austrianpainter.context.none")

        PaintSelection.Mode.POSITION -> lookedAtPos?.let {
            Component.translatable("austrianpainter.context.pos", it.x, it.y, it.z)
        } ?: Component.translatable("austrianpainter.context.none")
    }

    private fun targetLine(): Component = PaintSelection.target?.let {
        Component.translatable("austrianpainter.context.target", it.name)
    } ?: Component.translatable("austrianpainter.context.no_target")

    private fun drawRows(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val listHeight = listRows * ROW_HEIGHT
        graphics.fill(listX - 2, listY - 2, listX + listWidth + 2, listY + listHeight + 2, 0x60000000)

        if (rows.isEmpty()) {
            graphics.text(
                font,
                Component.translatable("austrianpainter.rules.empty"),
                listX + 2, listY + 3, 0xFF808080.toInt(),
            )
            return
        }

        graphics.enableScissor(listX, listY, listX + listWidth, listY + listHeight)
        for (index in 0 until listRows) {
            val row = rows.getOrNull(scroll + index) ?: break
            val y = listY + index * ROW_HEIGHT
            if (mouseX in listX..(listX + listWidth) && mouseY in y until y + ROW_HEIGHT) {
                graphics.fill(listX, y, listX + listWidth, y + ROW_HEIGHT, 0x60FF5555)
            }

            val icon = when (row) {
                is Row.Type -> row.target
                is Row.Donor -> row.donor
            }
            val stack = ItemStack(icon)
            if (!stack.isEmpty) graphics.item(stack, listX + 2, y - 1)

            graphics.text(font, describe(row), listX + 22, y + 3, 0xFFFFFFFF.toInt())
        }
        graphics.disableScissor()

        if (rowAt(mouseX.toDouble(), mouseY.toDouble()) != null) {
            graphics.setTooltipForNextFrame(
                Component.translatable("austrianpainter.rules.delete"), mouseX, mouseY,
            )
        }
    }

    private fun describe(row: Row): Component = when (row) {
        is Row.Type -> Component.translatable("austrianpainter.rule.type", row.source.name, row.target.name)
        is Row.Donor -> Component.translatable("austrianpainter.rule.donor", row.donor.name, row.count)
    }

    override fun isPauseScreen(): Boolean = false
}
