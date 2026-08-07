package com.maxisch.client.gui.tab

import com.maxisch.client.gui.BlockPickerScreen
import com.maxisch.client.gui.PainterScreen
import com.maxisch.client.gui.widget.RowListWidget
import com.maxisch.client.gui.widget.TextLineWidget
import com.maxisch.paint.ApSettings
import com.maxisch.paint.PaintStorage
import com.maxisch.paint.PresetStores
import com.maxisch.paint.session.PaintBrush
import com.maxisch.paint.session.PaintSelection
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block

/**
 * Arming the brush and undoing what it painted.
 *
 * Positions are never listed one by one - a preset can hold tens of thousands - so positional paint
 * is grouped by donor with a count. Removing a single position stays a world action: look at it and
 * press the erase key.
 */
class BrushTab(private val screen: PainterScreen) : ApTab("austrianpainter.tab.brush") {

    private companion object {
        const val MARGIN = 8
        const val ROW_HEIGHT = 14
        const val BUTTON_HEIGHT = 20
        const val GAP = 4
        const val GREY = 0xFFA0A0A0.toInt()
        const val YELLOW = 0xFFFFFF55.toInt()
    }

    private sealed interface Row {
        data class Type(val source: Block, val target: Block) : Row
        data class Donor(val donor: Block, val count: Int) : Row
    }

    private val presetLine = add(TextLineWidget(0, 0, 0, GREY))
    private val contextLine = add(TextLineWidget(0, 0, 0, GREY))
    private val targetLine = add(TextLineWidget(0, 0, 0, YELLOW))

    private val donorButton = add(
        Button.builder(Component.translatable("austrianpainter.pick_donor")) {
            Minecraft.getInstance().setScreenAndShow(
                BlockPickerScreen(screen) { block ->
                    PaintSelection.target = block
                    PaintBrush.donor = block
                },
            )
        }.width(110).build(),
    )

    private val brushButton = add(
        Button.builder(brushLabel()) {
            PaintBrush.enabled = !PaintBrush.enabled
            refresh()
        }.width(110).build(),
    )

    private val sizeButton = add(
        Button.builder(sizeLabel()) { cycleSize() }
            .width(90)
            .tooltip(hint())
            .build(),
    )

    private val modeButton = add(
        Button.builder(modeLabel()) { cycleMode() }.width(120).build(),
    )

    private val applyButton = add(
        Button.builder(Component.translatable("austrianpainter.apply")) { apply() }.width(90).build(),
    )

    private val clearButton = add(
        Button.builder(clearLabel()) {
            val cleared = PaintStorage.positionsByDonor().values.sum()
            PaintStorage.clearCurrentScope()
            screen.status(Component.translatable("austrianpainter.status.cleared_scope", cleared))
            refresh()
        }.width(140).build(),
    )

    private val list = add(
        RowListWidget<Row>(0, 0, 0, 0, ROW_HEIGHT).apply {
            emptyMessage = { Component.translatable("austrianpainter.rules.empty") }
            hoverColor = RowListWidget.DELETE_HOVER
            hoverTooltip = { Component.translatable("austrianpainter.rules.delete") }
            onRowClick = { row ->
                when (row) {
                    is Row.Type -> PaintStorage.removeTypeRule(row.source)
                    is Row.Donor -> PaintStorage.removeDonor(row.donor)
                }
                refresh()
            }
            drawRow = { graphics, row, x, y, _ ->
                val icon = when (row) {
                    is Row.Type -> row.target
                    is Row.Donor -> row.donor
                }
                val stack = ItemStack(icon)
                if (!stack.isEmpty) graphics.item(stack, x + 2, y - 1)
                graphics.text(
                    Minecraft.getInstance().font,
                    describe(row),
                    x + 22,
                    y + 3,
                    0xFFFFFFFF.toInt(),
                )
            }
        },
    )

    // ------------------------------------------------------------------ layout

    override fun doLayout(area: ScreenRectangle) {
        val x = area.left() + MARGIN
        val contentWidth = area.width() - MARGIN * 2
        var y = area.top() + GAP

        for (line in listOf(presetLine, contextLine, targetLine)) {
            line.setRectangle(contentWidth, TextLineWidget.HEIGHT, x, y)
            y += TextLineWidget.HEIGHT
        }
        y += GAP

        y = placeRow(x, y, donorButton, brushButton, sizeButton)
        y = placeRow(x, y, modeButton, applyButton, clearButton)
        y += GAP

        val listHeight = (area.bottom() - y - GAP).coerceAtLeast(ROW_HEIGHT)
        list.setRectangle(contentWidth, listHeight, x, y)
    }

    /** Lays buttons out left to right and returns the y the next row starts at. */
    private fun placeRow(startX: Int, y: Int, vararg buttons: Button): Int {
        var x = startX
        for (button in buttons) {
            button.setRectangle(button.width, BUTTON_HEIGHT, x, y)
            x += button.width + GAP
        }
        return y + BUTTON_HEIGHT + GAP
    }

    // ------------------------------------------------------------------ state

    override fun refresh() {
        list.rows = buildList {
            PaintStorage.typeRules().forEach { (source, target) -> add(Row.Type(source, target)) }
            PaintStorage.positionsByDonor().forEach { (donor, count) -> add(Row.Donor(donor, count)) }
        }

        presetLine.message = Component.translatable(
            "austrianpainter.active_presets",
            PresetStores.blocks.activeName,
            PresetStores.types.activeName,
        )
        contextLine.message = contextText()
        targetLine.message = targetText()

        brushButton.message = brushLabel()
        sizeButton.message = sizeLabel()
        modeButton.message = modeLabel()
        clearButton.message = clearLabel()
        applyButton.active = pendingIsValid()
    }

    private fun cycleSize() {
        val next = if (PaintBrush.radius >= PaintBrush.MAX_RADIUS) PaintBrush.MIN_RADIUS else PaintBrush.radius + 1
        PaintBrush.radius = next
        ApSettings.save()
        refresh()
    }

    private fun cycleMode() {
        val modes = PaintSelection.Mode.entries
        PaintSelection.mode = modes[(PaintSelection.mode.ordinal + 1) % modes.size]
        refresh()
    }

    private fun pendingIsValid(): Boolean {
        if (PaintSelection.target == null) return false
        return when (PaintSelection.mode) {
            PaintSelection.Mode.TYPE -> screen.lookedAtBlock != null
            PaintSelection.Mode.POSITION -> screen.lookedAtPos != null
        }
    }

    private fun apply() {
        val target = PaintSelection.target ?: return
        when (PaintSelection.mode) {
            PaintSelection.Mode.TYPE -> screen.lookedAtBlock?.let {
                PaintStorage.setTypeRule(it, target)
                screen.status(
                    Component.translatable("austrianpainter.status.rule_added", it.name, target.name),
                )
            }

            PaintSelection.Mode.POSITION -> screen.lookedAtPos?.let {
                val painted = PaintStorage.paintPositions(listOf(it), target)
                screen.status(Component.translatable("austrianpainter.status.applied", painted, target.name))
            }
        }
        refresh()
    }

    // ------------------------------------------------------------------ labels

    private fun brushLabel(): Component = Component.translatable(
        if (PaintBrush.enabled) "austrianpainter.brush.button_on" else "austrianpainter.brush.button_off",
        PaintBrush.radius,
    )

    private fun sizeLabel(): Component =
        Component.translatable("austrianpainter.brush.size", PaintBrush.radius)

    private fun modeLabel(): Component = Component.translatable(
        "austrianpainter.mode.label",
        Component.translatable("austrianpainter.mode.${PaintSelection.mode.name.lowercase()}"),
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

    private fun contextText(): Component = when (PaintSelection.mode) {
        PaintSelection.Mode.TYPE -> screen.lookedAtBlock?.let {
            Component.translatable("austrianpainter.context.type", it.name)
        } ?: Component.translatable("austrianpainter.context.none")

        PaintSelection.Mode.POSITION -> screen.lookedAtPos?.let {
            Component.translatable("austrianpainter.context.pos", it.x, it.y, it.z)
        } ?: Component.translatable("austrianpainter.context.none")
    }

    private fun targetText(): Component = PaintSelection.target?.let {
        Component.translatable("austrianpainter.context.target", it.name)
    } ?: Component.translatable("austrianpainter.context.no_target")

    private fun describe(row: Row): Component = when (row) {
        is Row.Type -> Component.translatable("austrianpainter.rule.type", row.source.name, row.target.name)
        is Row.Donor -> Component.translatable("austrianpainter.rule.donor", row.donor.name, row.count)
    }

    /** The hold-and-scroll gesture is invisible otherwise; [KeyHints] fills in the live key. */
    private fun hint() = com.maxisch.client.KeyHints.resizeTooltip()
}
