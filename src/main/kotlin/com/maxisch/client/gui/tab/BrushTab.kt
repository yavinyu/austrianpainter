package com.maxisch.client.gui.tab

import com.maxisch.client.keybind.KeyHints
import com.maxisch.client.gui.screen.BlockPickerScreen
import com.maxisch.client.gui.ConfirmAction
import com.maxisch.client.gui.screen.PainterScreen
import com.maxisch.client.gui.Theme
import com.maxisch.client.gui.widget.ActButtonWidget
import com.maxisch.client.gui.widget.CardWidget
import com.maxisch.client.gui.widget.SegmentedWidget
import com.maxisch.client.gui.widget.StepperWidget
import com.maxisch.client.gui.widget.ToggleSwitchWidget
import com.maxisch.client.gui.widget.RecentStripWidget
import com.maxisch.client.gui.widget.RowContent
import com.maxisch.client.gui.widget.RowListWidget
import com.maxisch.client.gui.widget.TextLineWidget
import com.maxisch.client.render.overlay.PaintedOverlay
import com.maxisch.paint.settings.ApSettings
import com.maxisch.paint.PaintStorage
import com.maxisch.paint.preset.PresetStores
import com.maxisch.paint.session.PaintBrush
import com.maxisch.paint.session.PaintSelection
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
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
        const val BUTTON_HEIGHT = 16
        const val GAP = 4
        const val TOGGLE_WIDTH = 30
        const val TOGGLE_HEIGHT = 14
        const val STEPPER_WIDTH = 62
        const val STEPPER_HEIGHT = 16
        const val GREY = 0xFFA0A0A0.toInt()
        const val YELLOW = 0xFFFFFF55.toInt()
    }

    private sealed interface Row {
        data class Type(val source: Block, val target: Block) : Row
        data class Donor(val donor: Block, val count: Int) : Row
    }

    // Cards come first: render order is add() order and they are the ground their controls sit on.
    private val donorCard = add(CardWidget(Component.translatable("austrianpainter.card.donor")))
    private val modeCard = add(CardWidget(Component.translatable("austrianpainter.card.mode")))
    private val listCard = add(
        CardWidget(Component.translatable("austrianpainter.card.rules")) {
            Component.literal(list.rows.size.toString())
        },
    )

    private val presetLine = add(TextLineWidget(0, 0, 0, GREY))
    private val contextLine = add(TextLineWidget(0, 0, 0, GREY))
    private val targetLine = add(TextLineWidget(0, 0, 0, YELLOW))

    private val modeSegmented = add(
        SegmentedWidget(
            options = PaintSelection.Mode.entries.map {
                Component.translatable("austrianpainter.mode.${it.name.lowercase()}")
            },
            selectedIndex = { PaintSelection.mode.ordinal },
            onSelect = { index ->
                PaintSelection.mode = PaintSelection.Mode.entries[index]
                refresh()
            },
        ),
    )

    private val radiusLabel = add(TextLineWidget(0, 0, 0, GREY)).also {
        it.message = Component.translatable("austrianpainter.brush.radius_label")
    }

    private val radiusStepper = add(
        StepperWidget(
            0, 0, 0, 0,
            PaintBrush.radius,
            PaintBrush.MIN_RADIUS,
            PaintBrush.MAX_RADIUS,
            1,
            { Component.literal(it.toString()) },
            { value ->
                PaintBrush.radius = value
                ApSettings.save()
                refresh()
            },
        ),
    )

    private val armLabel = add(TextLineWidget(0, 0, 0, GREY)).also {
        it.message = Component.translatable("austrianpainter.brush.arm")
    }

    private val armToggle = add(
        ToggleSwitchWidget(0, 0, 0, 0, PaintBrush.enabled, Theme.AMBER) {
            PaintBrush.enabled = !PaintBrush.enabled
            refresh()
        },
    ).also { it.setTooltip(KeyHints.eraseTooltip()) }

    private val overlayLabelLine = add(TextLineWidget(0, 0, 0, GREY)).also {
        it.message = Component.translatable("austrianpainter.overlay.label")
    }

    private val overlayToggle = add(
        ToggleSwitchWidget(0, 0, 0, 0, ApSettings.showPaintedOverlay, Theme.AMBER) {
            ApSettings.showPaintedOverlay = !ApSettings.showPaintedOverlay
            ApSettings.save()
            PaintedOverlay.invalidate()
            refresh()
        },
    ).also { it.setTooltip(KeyHints.overlayTooltip()) }

    private val recentStrip = add(
        RecentStripWidget(
            blocks = {
                ApSettings.recentDonorIds().mapNotNull { id ->
                    Identifier.tryParse(id)?.takeIf { BuiltInRegistries.BLOCK.containsKey(it) }
                        ?.let { BuiltInRegistries.BLOCK.getValue(it) }
                }
            },
            current = { PaintBrush.donor },
            onPick = { block ->
                PaintSelection.target = block
                PaintBrush.donor = block
                ApSettings.rememberDonor(BuiltInRegistries.BLOCK.getKey(block).toString())
                refresh()
            },
        ),
    )

    private val donorButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.pick_donor")) {
            Minecraft.getInstance().setScreenAndShow(
                BlockPickerScreen(screen) { block ->
                    PaintSelection.target = block
                    PaintBrush.donor = block
                },
            )
        }.width(110).build(),
    )

    private val applyButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.apply")) { apply() }
            .width(90)
            .variant(ActButtonWidget.Variant.PRIMARY)
            .build(),
    )

    private val clearButton = add(
        ActButtonWidget.builder(clearLabel()) {
            ConfirmAction.ask(screen, clearConfirmTitle(), clearConfirmMessage()) {
                val cleared = PaintStorage.positionsByDonor().values.sum()
                PaintStorage.clearCurrentScope()
                screen.status(Component.translatable("austrianpainter.status.cleared_scope", cleared))
                refresh()
            }
        }
            .width(140)
            .variant(ActButtonWidget.Variant.DANGER)
            .build(),
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
                // A type rule is a pair (this block renders as that one); a donor row is a single
                // block with a position count, so it draws one icon and puts the count on the right.
                val labelX = when (row) {
                    is Row.Type -> RowContent.pair(graphics, x, y, ROW_HEIGHT, row.source, row.target)
                    is Row.Donor -> RowContent.pair(graphics, x, y, ROW_HEIGHT, row.donor, null)
                }
                RowContent.label(
                    graphics,
                    labelX,
                    y,
                    ROW_HEIGHT,
                    // `width` is the list's own, from the apply receiver: naming `list` here would
                    // reference the property inside its own initialiser.
                    x + width,
                    text = when (row) {
                        is Row.Type -> row.target.name
                        is Row.Donor -> row.donor.name
                    },
                    trailing = (row as? Row.Donor)?.let { Component.literal(it.count.toString()) },
                )
            }
        },
    )

    // ------------------------------------------------------------------ layout

    override fun layout(area: ScreenRectangle) {
        val x = area.left() + MARGIN
        val contentWidth = area.width() - MARGIN * 2
        val top = area.top() + GAP

        // Two columns: what arms the brush on the left, what it has already done on the right.
        val leftWidth = (contentWidth - GAP) / 2
        val rightWidth = contentWidth - leftWidth - GAP
        val rightX = x + leftWidth + GAP
        val inner = leftWidth - CardWidget.PAD * 2
        val contentX = x + CardWidget.PAD

        // -------------------------------------------------- donor card
        var y = top + CardWidget.HEADER_HEIGHT
        targetLine.setRectangle(inner, TextLineWidget.HEIGHT, contentX, y)
        y += TextLineWidget.HEIGHT
        contextLine.setRectangle(inner, TextLineWidget.HEIGHT, contentX, y)
        y += TextLineWidget.HEIGHT + GAP
        donorButton.setRectangle(inner, BUTTON_HEIGHT, contentX, y)
        y += BUTTON_HEIGHT + GAP
        recentStrip.setRectangle(inner, RecentStripWidget.CELL, contentX, y)
        y += RecentStripWidget.CELL + CardWidget.PAD
        donorCard.setRectangle(leftWidth, y - top, x, top)

        // -------------------------------------------------- mode card
        val modeTop = y + GAP
        y = modeTop + CardWidget.HEADER_HEIGHT
        modeSegmented.setRectangle(inner, SegmentedWidget.HEIGHT, contentX, y)
        y += SegmentedWidget.HEIGHT + GAP

        y = controlRow(contentX, y, inner, radiusLabel, radiusStepper, STEPPER_WIDTH, STEPPER_HEIGHT)
        y = controlRow(contentX, y, inner, armLabel, armToggle, TOGGLE_WIDTH, TOGGLE_HEIGHT)
        y = controlRow(contentX, y, inner, overlayLabelLine, overlayToggle, TOGGLE_WIDTH, TOGGLE_HEIGHT)

        y += CardWidget.PAD - GAP
        modeCard.setRectangle(leftWidth, y - modeTop, x, modeTop)

        // -------------------------------------------------- actions, then the preset line under them
        y += GAP
        applyButton.setRectangle(applyButton.width, BUTTON_HEIGHT, x, y)
        clearButton.setRectangle(clearButton.width, BUTTON_HEIGHT, x + applyButton.width + GAP, y)
        y += BUTTON_HEIGHT + GAP
        presetLine.setRectangle(leftWidth, TextLineWidget.HEIGHT, x, y)

        // -------------------------------------------------- rules card, full height of the column
        val listCardHeight = (area.bottom() - top - GAP).coerceAtLeast(CardWidget.HEADER_HEIGHT + ROW_HEIGHT)
        listCard.setRectangle(rightWidth, listCardHeight, rightX, top)
        list.setRectangle(
            rightWidth - CardWidget.PAD * 2,
            listCardHeight - CardWidget.HEADER_HEIGHT - CardWidget.PAD,
            rightX + CardWidget.PAD,
            top + CardWidget.HEADER_HEIGHT,
        )
    }

    /** A label on the left of a card row with its control right-aligned; returns the next row's y. */
    private fun controlRow(
        x: Int,
        y: Int,
        width: Int,
        label: TextLineWidget,
        control: net.minecraft.client.gui.components.AbstractWidget,
        controlWidth: Int,
        controlHeight: Int,
    ): Int {
        val rowHeight = maxOf(controlHeight, TextLineWidget.HEIGHT)
        label.setRectangle(width - controlWidth - GAP, TextLineWidget.HEIGHT, x, y + (rowHeight - TextLineWidget.HEIGHT) / 2)
        control.setRectangle(controlWidth, controlHeight, x + width - controlWidth, y)
        return y + rowHeight + GAP
    }

    /** Lays buttons out left to right and returns the y the next row starts at. */
    private fun placeRow(startX: Int, y: Int, vararg buttons: ActButtonWidget): Int {
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

        clearButton.message = clearLabel()
        applyButton.active = pendingIsValid()

        // The controls hold their own state, so they are pushed rather than rebuilt - the brush can
        // also be armed and resized from the world, and this is where the tab catches up.
        armToggle.checked = PaintBrush.enabled
        overlayToggle.checked = ApSettings.showPaintedOverlay
        radiusStepper.value = PaintBrush.radius
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

    private fun clearConfirmTitle(): Component = Component.translatable("austrianpainter.clear_scope.confirm.title")

    /** Same room-vs-dimension distinction as [clearLabel], plus how many positions are on the line. */
    private fun clearConfirmMessage(): Component {
        val count = PaintStorage.positionsByDonor().values.sum()
        val room = PaintStorage.scope?.key
        return if (room == null) {
            Component.translatable("austrianpainter.clear_dimension.confirm.message", count)
        } else {
            Component.translatable("austrianpainter.clear_room.confirm.message", count, room)
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
}
