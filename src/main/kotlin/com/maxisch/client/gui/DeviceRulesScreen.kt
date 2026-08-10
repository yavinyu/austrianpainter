package com.maxisch.client.gui

import com.maxisch.client.gui.widget.RowListWidget
import com.maxisch.paint.ApSettings
import com.maxisch.paint.AreaTarget
import com.maxisch.paint.DeviceArray
import com.maxisch.paint.DeviceColumns
import com.maxisch.paint.DeviceSource
import com.maxisch.paint.PaintStorage
import com.maxisch.paint.PresetStores
import com.maxisch.paint.displayName
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/**
 * The eight device column rules: for each array, what its diorite and its polished diorite render
 * as. Reached from the dungeon settings rather than living as a sixth painter tab, because this is
 * configuration set once for one room on one floor, not part of the authoring loop.
 */
class DeviceRulesScreen(parent: Screen?) :
    ApScreen(Component.translatable("austrianpainter.device.title"), parent) {

    private data class Rule(val array: DeviceArray, val source: DeviceSource)

    private companion object {
        const val MARGIN = 8
        const val HEADER = 24
        const val ROW_HEIGHT = 16
        const val BUTTON_HEIGHT = 20
        const val GAP = 4
        const val GREY = 0xFFA0A0A0.toInt()
    }

    private val rules =
        DeviceArray.entries.flatMap { array -> DeviceSource.entries.map { Rule(array, it) } }

    private var selected: Rule? = null

    /**
     * Rebuilding the layer costs a full view rebuild, so eight edits are applied as one on close
     * rather than one at a time.
     */
    private var dirty = false

    private lateinit var list: RowListWidget<Rule>
    private lateinit var enableButton: Button
    private lateinit var seedButton: Button
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
        seedButton = addRenderableWidget(
            Button.builder(seedLabel()) { toggleSeed() }
                .bounds(MARGIN + 124, y, 140, BUTTON_HEIGHT).build(),
        )
        seedButton.setTooltip(Tooltip.create(Component.translatable("austrianpainter.device.seed_hint")))
        addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.device.reset")) { resetRules() }
                .bounds(MARGIN + 268, y, 120, BUTTON_HEIGHT).build(),
        )
        y += BUTTON_HEIGHT + GAP * 2

        val listHeight =
            (height - y - BUTTON_HEIGHT * 2 - GAP * 3 - MARGIN).coerceAtLeast(ROW_HEIGHT)
        list = addRenderableWidget(
            RowListWidget<Rule>(MARGIN, y, contentWidth, listHeight, ROW_HEIGHT).apply {
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
        ApSettings.deviceEnabled = !ApSettings.deviceEnabled
        ApSettings.save()
        enableButton.message = enableLabel()
        dirty = true
    }

    private fun toggleSeed() {
        ApSettings.deviceSeedPerColumn = !ApSettings.deviceSeedPerColumn
        ApSettings.save()
        seedButton.message = seedLabel()
        dirty = true
    }

    private fun resetRules() {
        ApSettings.resetDeviceRules()
        dirty = true
        refreshButtons()
    }

    private fun pickDonor() {
        val rule = selected ?: return
        Minecraft.getInstance().setScreenAndShow(
            BlockPickerScreen(this) { block -> setRule(rule, AreaTarget.Donor(block)) },
        )
    }

    /**
     * Cycles rather than opening a chooser: there are rarely more than a handful of palettes, and a
     * second modal for a list that short would cost more clicks than it saves.
     */
    private fun cyclePalette() {
        val rule = selected ?: return
        val names = PresetStores.palettes.listWithActive()
        if (names.isEmpty()) return

        val current = ApSettings.deviceRule(rule.array, rule.source)
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

    private fun setRule(rule: Rule, target: AreaTarget?) {
        ApSettings.setDeviceRule(rule.array, rule.source, target)
        selected = rule
        dirty = true
        refreshButtons()
    }

    override fun onClose() {
        if (dirty) {
            dirty = false
            DeviceColumns.invalidate()
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
        if (ApSettings.deviceEnabled) "austrianpainter.device.on" else "austrianpainter.device.off",
    )

    private fun seedLabel(): Component = Component.translatable(
        if (ApSettings.deviceSeedPerColumn) {
            "austrianpainter.device.seed_column"
        } else {
            "austrianpainter.device.seed_block"
        },
    )

    private fun drawRule(graphics: GuiGraphicsExtractor, rule: Rule, x: Int, y: Int) {
        val sourceStack = ItemStack(rule.source.block)
        if (!sourceStack.isEmpty) graphics.item(sourceStack, x + 2, y)

        val target = ApSettings.deviceRule(rule.array, rule.source)
        if (target is AreaTarget.Donor) {
            val donorStack = ItemStack(target.block)
            if (!donorStack.isEmpty) graphics.item(donorStack, x + 20, y)
        }

        val label = if (target == null) {
            Component.translatable(
                "austrianpainter.device.row_off",
                arrayName(rule.array),
                rule.source.block.name,
            )
        } else {
            Component.translatable(
                "austrianpainter.device.row",
                arrayName(rule.array),
                rule.source.block.name,
                target.displayName(),
            )
        }
        graphics.text(font, label, x + 40, y + 4, 0xFFFFFFFF.toInt())
    }

    private fun arrayName(array: DeviceArray): Component =
        Component.translatable("austrianpainter.device.array.${array.key}")

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        graphics.centeredText(font, title, width / 2, 8, 0xFFFFFFFF.toInt())
        if (!DeviceColumns.shouldApply()) {
            graphics.text(
                font,
                Component.translatable("austrianpainter.device.inactive"),
                MARGIN,
                height - 40,
                GREY,
            )
        }
    }
}
