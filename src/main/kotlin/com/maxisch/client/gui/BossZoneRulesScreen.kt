package com.maxisch.client.gui

import com.maxisch.client.gui.widget.RowListWidget
import com.maxisch.paint.ApSettings
import com.maxisch.paint.AreaTarget
import com.maxisch.paint.BossZones
import com.maxisch.paint.PaintStorage
import com.maxisch.paint.PresetStores
import com.maxisch.paint.ZoneSourceRule
import com.maxisch.paint.displayName
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/**
 * The boss-zone rules: for each fixed F7/M7 Sadan-room zone, what each of its source blocks (and,
 * for the lamp zone, lit/unlit state) renders as. Same reasoning as [DeviceRulesScreen] for living
 * as its own screen rather than a painter tab - this is configuration set once for one room on one
 * floor, not part of the authoring loop.
 */
class BossZoneRulesScreen(parent: Screen?) :
    ApScreen(Component.translatable("austrianpainter.zones.title"), parent) {

    private companion object {
        const val MARGIN = 8
        const val HEADER = 24
        const val ROW_HEIGHT = 16
        const val BUTTON_HEIGHT = 20
        const val GAP = 4
        const val GREY = 0xFFA0A0A0.toInt()
    }

    private val rules = BossZones.RULES

    private var selected: ZoneSourceRule? = null

    /** Rebuilding the layer costs a full view rebuild, so several edits are applied as one on close
     *  rather than one at a time. */
    private var dirty = false

    private lateinit var list: RowListWidget<ZoneSourceRule>
    private lateinit var enableButton: Button
    private lateinit var donorButton: Button
    private lateinit var paletteButton: Button
    private lateinit var clearButton: Button

    override fun init() {
        val contentWidth = width - MARGIN * 2
        var y = HEADER

        enableButton = addRenderableWidget(
            Button.builder(enableLabel()) { toggleEnabled() }
                .bounds(MARGIN, y, 120, BUTTON_HEIGHT).build(),
        )
        addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.zones.reset")) { resetRules() }
                .bounds(MARGIN + 124, y, 120, BUTTON_HEIGHT).build(),
        )
        y += BUTTON_HEIGHT + GAP * 2

        val listHeight = (height - y - BUTTON_HEIGHT * 2 - GAP * 3 - MARGIN).coerceAtLeast(ROW_HEIGHT)
        list = addRenderableWidget(
            RowListWidget<ZoneSourceRule>(MARGIN, y, contentWidth, listHeight, ROW_HEIGHT).apply {
                isSelected = { it == selected }
                onRowClick = { rule ->
                    selected = rule
                    refreshButtons()
                }
                drawRow = { graphics, rule, x, rowY, _ -> drawRule(graphics, rule, x, rowY) }
            },
        )
        list.rows = rules
        y += listHeight + GAP

        donorButton = addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.device.pick_donor")) { pickDonor() }
                .bounds(MARGIN, y, 120, BUTTON_HEIGHT).build(),
        )
        paletteButton = addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.device.use_palette")) { cyclePalette() }
                .bounds(MARGIN + 124, y, 120, BUTTON_HEIGHT).build(),
        )
        clearButton = addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.device.clear_rule")) { clearRule() }
                .bounds(MARGIN + 248, y, 120, BUTTON_HEIGHT).build(),
        )

        addRenderableWidget(
            Button.builder(Component.translatable("gui.done")) { onClose() }
                .bounds(width / 2 - 50, height - 26, 100, 20).build(),
        )

        refreshButtons()
    }

    // ------------------------------------------------------------------ mutation

    private fun toggleEnabled() {
        ApSettings.zonesEnabled = !ApSettings.zonesEnabled
        ApSettings.save()
        enableButton.message = enableLabel()
        dirty = true
    }

    private fun resetRules() {
        ApSettings.resetZoneRules()
        dirty = true
        refreshButtons()
    }

    private fun pickDonor() {
        val rule = selected ?: return
        Minecraft.getInstance().setScreenAndShow(
            BlockPickerScreen(this) { block -> setRule(rule, AreaTarget.Donor(block)) },
        )
    }

    private fun cyclePalette() {
        val rule = selected ?: return
        val names = PresetStores.palettes.listWithActive()
        if (names.isEmpty()) return

        val current = ApSettings.zoneRule(rule)
        val next = if (current is AreaTarget.Palette) {
            names[(names.indexOf(current.name) + 1).mod(names.size)]
        } else {
            names.first()
        }
        setRule(rule, AreaTarget.Palette(next))
    }

    private fun clearRule() {
        val rule = selected ?: return
        setRule(rule, null)
    }

    private fun setRule(rule: ZoneSourceRule, target: AreaTarget?) {
        ApSettings.setZoneRule(rule, target)
        selected = rule
        dirty = true
        refreshButtons()
    }

    override fun onClose() {
        if (dirty) {
            dirty = false
            BossZones.invalidate()
            PaintStorage.onDeviceScopeChanged()
        }
        super.onClose()
    }

    // ------------------------------------------------------------------ render

    private fun refreshButtons() {
        val hasSelection = selected != null
        donorButton.active = hasSelection
        clearButton.active = hasSelection
        paletteButton.active = hasSelection && PresetStores.palettes.listWithActive().isNotEmpty()
        paletteButton.setTooltip(
            if (paletteButton.active) null
            else Tooltip.create(Component.translatable("austrianpainter.device.no_palettes")),
        )
    }

    private fun enableLabel(): Component = Component.translatable(
        if (ApSettings.zonesEnabled) "austrianpainter.zones.on" else "austrianpainter.zones.off",
    )

    private fun drawRule(graphics: GuiGraphicsExtractor, rule: ZoneSourceRule, x: Int, y: Int) {
        val sourceStack = ItemStack(rule.block)
        if (!sourceStack.isEmpty) graphics.item(sourceStack, x + 2, y)

        val target = ApSettings.zoneRule(rule)
        if (target is AreaTarget.Donor) {
            val donorStack = ItemStack(target.block)
            if (!donorStack.isEmpty) graphics.item(donorStack, x + 20, y)
        }

        val label = if (target == null) {
            Component.translatable("austrianpainter.zones.row_off", zoneName(rule), sourceName(rule))
        } else {
            Component.translatable("austrianpainter.zones.row", zoneName(rule), sourceName(rule), target.displayName())
        }
        graphics.text(font, label, x + 40, y + 4, 0xFFFFFFFF.toInt())
    }

    private fun zoneName(rule: ZoneSourceRule): Component =
        Component.translatable("austrianpainter.zones.zone.${rule.zone.key}")

    private fun sourceName(rule: ZoneSourceRule): Component = when (rule.lit) {
        true -> Component.translatable("austrianpainter.zones.lit", rule.block.name)
        false -> Component.translatable("austrianpainter.zones.unlit", rule.block.name)
        null -> rule.block.name
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        graphics.centeredText(font, title, width / 2, 8, 0xFFFFFFFF.toInt())
        if (!BossZones.shouldApply()) {
            graphics.text(
                font,
                Component.translatable("austrianpainter.zones.inactive"),
                MARGIN,
                height - 40,
                GREY,
            )
        }
    }
}
