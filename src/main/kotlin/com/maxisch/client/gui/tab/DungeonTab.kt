package com.maxisch.client.gui.tab

import com.maxisch.client.gui.BlockPickerScreen
import com.maxisch.client.gui.PainterScreen
import com.maxisch.client.gui.widget.RowListWidget
import com.maxisch.client.gui.widget.TextLineWidget
import com.maxisch.paint.ApSettings
import com.maxisch.paint.AreaTarget
import com.maxisch.paint.BossZone
import com.maxisch.paint.BossZones
import com.maxisch.paint.DeviceArray
import com.maxisch.paint.DeviceColumns
import com.maxisch.paint.PaintStorage
import com.maxisch.paint.PresetStores
import com.maxisch.paint.ZoneSourceRule
import com.maxisch.paint.displayName
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/**
 * Every F7/M7 repaint mechanic in one place: the four device-column arrays on the left, the five
 * Sadan boss-zone rules (Pillars, S1-S4) on the right. Two independent lists side by side rather
 * than one long stacked one, because they are genuinely two separate rule sets that each need
 * their own enable/reset controls - a single shared editor row at the bottom acts on whichever
 * list was clicked most recently.
 *
 * Edits batch into one rebuild on [onHidden] rather than one per click, same reasoning the two
 * screens this tab replaces already had: rebuilding the layer costs a full view rebuild.
 */
class DungeonTab(private val screen: PainterScreen) : ApTab("austrianpainter.tab.dungeon") {

    private companion object {
        const val MARGIN = 8
        const val ROW_HEIGHT = 16
        const val BUTTON_HEIGHT = 20
        const val GAP = 4

        // Device-array accents match each array's real in-game glass colour.
        const val DEVICE_GREEN = 0xFF55FF55.toInt()
        const val DEVICE_YELLOW = 0xFFFFFF55.toInt()
        const val DEVICE_PURPLE = 0xFFAA00AA.toInt()
        const val DEVICE_RED = 0xFFFF5555.toInt()

        // Zone accents are a distinct 5-way palette, not literal block colours - the zones have no
        // single colour identity the way the device arrays do.
        const val ZONE_PILLARS = 0xFF808080.toInt()
        const val ZONE_S1 = 0xFF55FFFF.toInt()
        const val ZONE_S2 = 0xFFFF5555.toInt()
        const val ZONE_S3 = 0xFF5599FF.toInt()
        const val ZONE_S4 = 0xFF55FF55.toInt()

        const val GREY = 0xFFA0A0A0.toInt()
        const val YELLOW = 0xFFFFFF55.toInt()
    }

    private sealed interface Selection {
        data class Device(val rule: DeviceRule) : Selection
        data class Zone(val rule: ZoneSourceRule) : Selection
    }

    private var selection: Selection? = null
    private var dirty = false

    private val font get() = Minecraft.getInstance().font

    // ------------------------------------------------------------------ widgets - device panel

    private val deviceHeaderLine = add(TextLineWidget(0, 0, 0, GREY))

    private val deviceEnableButton = add(
        Button.builder(deviceEnableLabel()) { toggleDeviceEnabled() }.width(90).build(),
    )

    private val deviceSeedButton = add(
        Button.builder(deviceSeedLabel()) { toggleDeviceSeed() }
            .width(100)
            .tooltip(Tooltip.create(Component.translatable("austrianpainter.device.seed_hint")))
            .build(),
    )

    private val deviceResetButton = add(
        Button.builder(Component.translatable("austrianpainter.device.reset")) { resetDeviceRules() }
            .width(110).build(),
    )

    private val deviceList = add(
        RowListWidget<DeviceRule>(0, 0, 0, 0, ROW_HEIGHT).apply {
            rows = DungeonRulesLogic.deviceRules
            isSelected = { (selection as? Selection.Device)?.rule == it }
            onRowClick = { rule -> selection = Selection.Device(rule); refreshEditor() }
            drawRow = { graphics, rule, x, y, _ -> drawDeviceRow(graphics, rule, x, y) }
        },
    )

    // ------------------------------------------------------------------ widgets - boss-zone panel

    private val zoneHeaderLine = add(TextLineWidget(0, 0, 0, GREY))

    private val zoneEnableButton = add(
        Button.builder(zoneEnableLabel()) { toggleZonesEnabled() }.width(90).build(),
    )

    private val zoneResetButton = add(
        Button.builder(Component.translatable("austrianpainter.zones.reset")) { resetZoneRules() }
            .width(110).build(),
    )

    private val zoneList = add(
        RowListWidget<ZoneSourceRule>(0, 0, 0, 0, ROW_HEIGHT).apply {
            rows = BossZones.RULES
            isSelected = { (selection as? Selection.Zone)?.rule == it }
            onRowClick = { rule -> selection = Selection.Zone(rule); refreshEditor() }
            drawRow = { graphics, rule, x, y, _ -> drawZoneRow(graphics, rule, x, y) }
        },
    )

    // ------------------------------------------------------------------ shared editor row

    private val editingLine = add(TextLineWidget(0, 0, 0, YELLOW))

    private val donorButton = add(
        Button.builder(Component.translatable("austrianpainter.device.pick_donor")) { pickDonor() }
            .width(120).build(),
    )

    private val paletteButton = add(
        Button.builder(Component.translatable("austrianpainter.device.use_palette")) { cyclePalette() }
            .width(120).build(),
    )

    private val clearButton = add(
        Button.builder(Component.translatable("austrianpainter.device.clear_rule")) { clearRule() }
            .width(110).build(),
    )

    init {
        refreshEditor()
    }

    // ------------------------------------------------------------------ layout

    override fun doLayout(area: ScreenRectangle) {
        val x = area.left() + MARGIN
        val contentWidth = area.width() - MARGIN * 2
        val leftWidth = (contentWidth - GAP * 2) / 2
        val rightWidth = contentWidth - leftWidth - GAP * 2
        val rightX = x + leftWidth + GAP * 2

        var y = area.top() + GAP
        deviceHeaderLine.setRectangle(leftWidth, TextLineWidget.HEIGHT, x, y)
        zoneHeaderLine.setRectangle(rightWidth, TextLineWidget.HEIGHT, rightX, y)
        y += TextLineWidget.HEIGHT + GAP

        deviceEnableButton.setRectangle(deviceEnableButton.width, BUTTON_HEIGHT, x, y)
        deviceSeedButton.setRectangle(
            deviceSeedButton.width,
            BUTTON_HEIGHT,
            x + deviceEnableButton.width + GAP,
            y,
        )
        zoneEnableButton.setRectangle(zoneEnableButton.width, BUTTON_HEIGHT, rightX, y)
        y += BUTTON_HEIGHT + GAP

        deviceResetButton.setRectangle(deviceResetButton.width, BUTTON_HEIGHT, x, y)
        zoneResetButton.setRectangle(zoneResetButton.width, BUTTON_HEIGHT, rightX, y)
        y += BUTTON_HEIGHT + GAP * 2

        val editorRowY = area.bottom() - BUTTON_HEIGHT - GAP
        val editingLineY = editorRowY - TextLineWidget.HEIGHT - GAP

        val listHeight = (editingLineY - y - GAP).coerceAtLeast(ROW_HEIGHT)
        deviceList.setRectangle(leftWidth, listHeight, x, y)
        zoneList.setRectangle(rightWidth, listHeight, rightX, y)

        editingLine.setRectangle(contentWidth, TextLineWidget.HEIGHT, x, editingLineY)
        donorButton.setRectangle(donorButton.width, BUTTON_HEIGHT, x, editorRowY)
        paletteButton.setRectangle(
            paletteButton.width,
            BUTTON_HEIGHT,
            x + donorButton.width + GAP,
            editorRowY,
        )
        clearButton.setRectangle(
            clearButton.width,
            BUTTON_HEIGHT,
            x + donorButton.width + paletteButton.width + GAP * 2,
            editorRowY,
        )
    }

    // ------------------------------------------------------------------ mutation

    private fun toggleDeviceEnabled() {
        ApSettings.deviceEnabled = !ApSettings.deviceEnabled
        ApSettings.save()
        deviceEnableButton.message = deviceEnableLabel()
        deviceHeaderLine.message = deviceHeaderText()
        dirty = true
    }

    private fun toggleDeviceSeed() {
        ApSettings.deviceSeedPerColumn = !ApSettings.deviceSeedPerColumn
        ApSettings.save()
        deviceSeedButton.message = deviceSeedLabel()
        dirty = true
    }

    private fun resetDeviceRules() {
        ApSettings.resetDeviceRules()
        dirty = true
        refreshEditor()
    }

    private fun toggleZonesEnabled() {
        ApSettings.zonesEnabled = !ApSettings.zonesEnabled
        ApSettings.save()
        zoneEnableButton.message = zoneEnableLabel()
        zoneHeaderLine.message = zoneHeaderText()
        dirty = true
    }

    private fun resetZoneRules() {
        ApSettings.resetZoneRules()
        dirty = true
        refreshEditor()
    }

    private fun pickDonor() {
        when (val sel = selection) {
            is Selection.Device -> Minecraft.getInstance().setScreenAndShow(
                BlockPickerScreen(screen) { block -> setDeviceRule(sel.rule, AreaTarget.Donor(block)) },
            )

            is Selection.Zone -> Minecraft.getInstance().setScreenAndShow(
                BlockPickerScreen(screen) { block -> setZoneRule(sel.rule, AreaTarget.Donor(block)) },
            )

            null -> Unit
        }
    }

    private fun cyclePalette() {
        when (val sel = selection) {
            is Selection.Device -> {
                val current = ApSettings.deviceRule(sel.rule.array, sel.rule.source)
                val next = DungeonRulesLogic.nextPalette(current) ?: return
                setDeviceRule(sel.rule, AreaTarget.Palette(next))
            }

            is Selection.Zone -> {
                val current = ApSettings.zoneRule(sel.rule)
                val next = DungeonRulesLogic.nextPalette(current) ?: return
                setZoneRule(sel.rule, AreaTarget.Palette(next))
            }

            null -> Unit
        }
    }

    private fun clearRule() {
        when (val sel = selection) {
            is Selection.Device -> setDeviceRule(sel.rule, null)
            is Selection.Zone -> setZoneRule(sel.rule, null)
            null -> Unit
        }
    }

    private fun setDeviceRule(rule: DeviceRule, target: AreaTarget?) {
        ApSettings.setDeviceRule(rule.array, rule.source, target)
        selection = Selection.Device(rule)
        dirty = true
        refreshEditor()
    }

    private fun setZoneRule(rule: ZoneSourceRule, target: AreaTarget?) {
        ApSettings.setZoneRule(rule, target)
        selection = Selection.Zone(rule)
        dirty = true
        refreshEditor()
    }

    override fun onHidden() {
        if (!dirty) return
        dirty = false
        DeviceColumns.invalidate()
        BossZones.invalidate()
        PaintStorage.onDeviceScopeChanged()
    }

    // ------------------------------------------------------------------ state

    override fun refresh() {
        deviceHeaderLine.message = deviceHeaderText()
        zoneHeaderLine.message = zoneHeaderText()
        refreshEditor()
    }

    private fun refreshEditor() {
        val hasSelection = selection != null
        donorButton.active = hasSelection
        clearButton.active = hasSelection
        paletteButton.active = hasSelection && PresetStores.palettes.listWithActive().isNotEmpty()
        paletteButton.setTooltip(
            if (paletteButton.active) {
                null
            } else {
                Tooltip.create(Component.translatable("austrianpainter.device.no_palettes"))
            },
        )
        editingLine.message = when (val sel = selection) {
            is Selection.Device -> Component.translatable(
                "austrianpainter.dungeon.editing_device",
                arrayName(sel.rule),
                sel.rule.source.block.name,
            )

            is Selection.Zone -> Component.translatable(
                "austrianpainter.dungeon.editing_zone",
                zoneName(sel.rule),
                sourceName(sel.rule),
            )

            null -> Component.translatable("austrianpainter.dungeon.editing_none")
        }
    }

    // ------------------------------------------------------------------ labels

    private fun deviceEnableLabel(): Component = Component.translatable(
        if (ApSettings.deviceEnabled) "austrianpainter.device.on" else "austrianpainter.device.off",
    )

    private fun deviceSeedLabel(): Component = Component.translatable(
        if (ApSettings.deviceSeedPerColumn) {
            "austrianpainter.device.seed_column"
        } else {
            "austrianpainter.device.seed_block"
        },
    )

    private fun zoneEnableLabel(): Component = Component.translatable(
        if (ApSettings.zonesEnabled) "austrianpainter.zones.on" else "austrianpainter.zones.off",
    )

    private fun deviceHeaderText(): Component = Component.translatable(
        "austrianpainter.dungeon.device_header",
        if (DeviceColumns.shouldApply()) {
            Component.translatable("austrianpainter.device.active")
        } else {
            Component.translatable("austrianpainter.device.inactive")
        },
    )

    private fun zoneHeaderText(): Component = Component.translatable(
        "austrianpainter.dungeon.zone_header",
        if (BossZones.shouldApply()) {
            Component.translatable("austrianpainter.zones.active")
        } else {
            Component.translatable("austrianpainter.zones.inactive")
        },
    )

    private fun arrayName(rule: DeviceRule): Component =
        Component.translatable("austrianpainter.device.array.${rule.array.key}")

    private fun zoneName(rule: ZoneSourceRule): Component =
        Component.translatable("austrianpainter.zones.zone.${rule.zone.key}")

    private fun sourceName(rule: ZoneSourceRule): Component = when (rule.lit) {
        true -> Component.translatable("austrianpainter.zones.lit", rule.block.name)
        false -> Component.translatable("austrianpainter.zones.unlit", rule.block.name)
        null -> rule.block.name
    }

    private fun deviceAccent(rule: DeviceRule): Int = when (rule.array) {
        DeviceArray.GREEN -> DEVICE_GREEN
        DeviceArray.YELLOW -> DEVICE_YELLOW
        DeviceArray.PURPLE -> DEVICE_PURPLE
        DeviceArray.RED -> DEVICE_RED
    }

    private fun zoneAccent(zone: BossZone): Int = when (zone) {
        BossZone.PILLARS -> ZONE_PILLARS
        BossZone.S1 -> ZONE_S1
        BossZone.S2 -> ZONE_S2
        BossZone.S3 -> ZONE_S3
        BossZone.S4 -> ZONE_S4
    }

    // ------------------------------------------------------------------ row rendering

    private fun drawDeviceRow(
        graphics: GuiGraphicsExtractor,
        rule: DeviceRule,
        x: Int,
        y: Int,
    ) {
        graphics.fill(x, y, x + 3, y + ROW_HEIGHT, deviceAccent(rule))

        val sourceStack = ItemStack(rule.source.block)
        if (!sourceStack.isEmpty) graphics.item(sourceStack, x + 6, y)

        val target = ApSettings.deviceRule(rule.array, rule.source)
        if (target is AreaTarget.Donor) {
            val donorStack = ItemStack(target.block)
            if (!donorStack.isEmpty) graphics.item(donorStack, x + 24, y)
        }

        val label = if (target == null) {
            Component.translatable("austrianpainter.device.row_off", arrayName(rule), rule.source.block.name)
        } else {
            Component.translatable(
                "austrianpainter.device.row",
                arrayName(rule),
                rule.source.block.name,
                target.displayName(),
            )
        }
        graphics.text(font, label, x + 44, y + 4, 0xFFFFFFFF.toInt())
    }

    private fun drawZoneRow(
        graphics: GuiGraphicsExtractor,
        rule: ZoneSourceRule,
        x: Int,
        y: Int,
    ) {
        graphics.fill(x, y, x + 3, y + ROW_HEIGHT, zoneAccent(rule.zone))

        val sourceStack = ItemStack(rule.block)
        if (!sourceStack.isEmpty) graphics.item(sourceStack, x + 6, y)

        val target = ApSettings.zoneRule(rule)
        if (target is AreaTarget.Donor) {
            val donorStack = ItemStack(target.block)
            if (!donorStack.isEmpty) graphics.item(donorStack, x + 24, y)
        }

        val label = if (target == null) {
            Component.translatable("austrianpainter.zones.row_off", zoneName(rule), sourceName(rule))
        } else {
            Component.translatable("austrianpainter.zones.row", zoneName(rule), sourceName(rule), target.displayName())
        }
        graphics.text(font, label, x + 44, y + 4, 0xFFFFFFFF.toInt())
    }
}
