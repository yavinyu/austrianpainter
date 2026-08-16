package com.maxisch.client.gui.tab

import com.maxisch.client.gui.screen.BlockPickerScreen
import com.maxisch.client.gui.screen.PainterScreen
import com.maxisch.client.gui.screen.ReplaceFluidBossScreen
import com.maxisch.client.gui.Theme
import com.maxisch.client.gui.nvgSurface
import com.maxisch.client.render.render2d.NVGUtils
import com.maxisch.client.gui.widget.ActButtonWidget
import com.maxisch.client.gui.widget.CardWidget
import com.maxisch.client.gui.widget.RowContent
import com.maxisch.client.gui.widget.RowListWidget
import com.maxisch.client.gui.widget.TextLineWidget
import com.maxisch.paint.settings.ApSettings
import com.maxisch.paint.rule.AreaTarget
import com.maxisch.paint.rule.BossZone
import com.maxisch.paint.rule.BossZones
import com.maxisch.paint.rule.DeviceArray
import com.maxisch.paint.rule.DeviceColumns
import com.maxisch.paint.PaintStorage
import com.maxisch.paint.preset.PresetStores
import com.maxisch.paint.rule.ZoneSourceRule
import com.maxisch.paint.rule.displayName
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/**
 * Every F7/M7 repaint mechanic in one place, presented as Sadan's three real boss phases rather than
 * the two implementation-shaped groups this mod originally split them into:
 *
 * - **P1 "Conveyer"** - every zone with [BossZone.p1] set (the coal-block/player-head zone plus
 *   any other zone that happens to live in this phase, e.g. a second, unrelated crusher).
 * - **P2 "Pillars"** - the four moving diorite arrays (`DeviceArray`/[DeviceColumns]).
 * - **P3 "Devices"** - every zone with [BossZone.p1] unset (`BossZone.S1`-`S4`, `CRUSHER`, ...).
 *
 * Three independent lists side by side rather than one long stacked one - they are three genuinely
 * separate rule sets, each with its own Live toggle and Reset, and a single shared editor row at the
 * bottom acts on whichever list was clicked most recently. P1 and P3 both draw from
 * [BossZones.RULES] (filtered by [BossZone.p1]) and share its one rule map underneath - only their
 * Live flag and Reset scope are independent, see [ApSettings.conveyorEnabled]/[ApSettings.zonesEnabled].
 *
 * Edits batch into one rebuild on [onHidden] rather than one per click, same reasoning the two
 * screens this tab replaces already had: rebuilding the layer costs a full view rebuild.
 */
class DungeonTab(private val screen: PainterScreen) : ApTab("austrianpainter.tab.dungeon") {

    private companion object {
        const val MARGIN = 8
        const val ROW_HEIGHT = 16

        /** Accent bar identifying a rule's array or zone. */
        const val ACCENT_WIDTH = 3
        const val ACCENT_INSET = 2
        const val ACCENT_RADIUS = 1.5f
        const val BUTTON_HEIGHT = 16
        const val GAP = 4

        // P2 array accents match each array's real in-game glass colour.
        const val DEVICE_GREEN = 0xFF55FF55.toInt()
        const val DEVICE_YELLOW = 0xFFFFFF55.toInt()
        const val DEVICE_PURPLE = 0xFFAA00AA.toInt()
        const val DEVICE_RED = 0xFFFF5555.toInt()

        // Zone accents (P1 + P3) are a distinct 5-way palette, not literal block colours - the
        // zones have no single colour identity the way the P2 arrays do.
        const val ZONE_PILLARS = 0xFF808080.toInt()
        const val ZONE_S1 = 0xFF55FFFF.toInt()
        const val ZONE_S2 = 0xFFFF5555.toInt()
        const val ZONE_S3 = 0xFF5599FF.toInt()
        const val ZONE_S4 = 0xFF55FF55.toInt()
        const val ZONE_CRUSHER = 0xFFC08040.toInt()

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

    // ------------------------------------------------------------------ widgets - P1 conveyer

    // Cards first: they are the ground their lists draw on top of.
    private val p1Card = add(
        CardWidget(Component.translatable("austrianpainter.card.p1")) {
            Component.literal(BossZones.RULES.count { it.zone.p1 }.toString())
        },
    )
    private val p2Card = add(
        CardWidget(Component.translatable("austrianpainter.card.p2")) {
            Component.literal(DungeonRulesLogic.deviceRules.size.toString())
        },
    )
    private val p3Card = add(
        CardWidget(Component.translatable("austrianpainter.card.p3")) {
            Component.literal(BossZones.RULES.count { !it.zone.p1 }.toString())
        },
    )

    private val p1HeaderLine = add(TextLineWidget(0, 0, 0, GREY))

    private val conveyorEnableButton = add(
        ActButtonWidget.builder(conveyorEnableLabel()) { toggleConveyorEnabled() }
            .width(90)
            .tooltip(Tooltip.create(Component.translatable("austrianpainter.zones.live_hint")))
            .build(),
    )

    private val conveyorResetButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.conveyor.reset")) { resetConveyorRules() }
            .width(110).build(),
    )

    private val p1List = add(
        RowListWidget<ZoneSourceRule>(0, 0, 0, 0, ROW_HEIGHT).apply {
            rows = BossZones.RULES.filter { it.zone.p1 }
            isSelected = { (selection as? Selection.Zone)?.rule == it }
            onRowClick = { rule -> selection = Selection.Zone(rule); refreshEditor() }
            drawRow = { graphics, rule, x, y, _ -> drawZoneRow(graphics, rule, x, y, showZoneTag = true) }
        },
    )

    // ------------------------------------------------------------------ widgets - P2 pillars

    private val p2HeaderLine = add(TextLineWidget(0, 0, 0, GREY))

    private val deviceEnableButton = add(
        ActButtonWidget.builder(deviceEnableLabel()) { toggleDeviceEnabled() }
            .width(90)
            // The status line says only whether the rules are live; where they become live is a
            // sentence, and a sentence does not fit in half a tab. It lives here instead.
            .tooltip(Tooltip.create(Component.translatable("austrianpainter.device.live_hint")))
            .build(),
    )

    private val deviceResetButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.device.reset")) { resetDeviceRules() }
            .width(110).build(),
    )

    private val p2List = add(
        RowListWidget<DeviceRule>(0, 0, 0, 0, ROW_HEIGHT).apply {
            rows = DungeonRulesLogic.deviceRules
            isSelected = { (selection as? Selection.Device)?.rule == it }
            onRowClick = { rule -> selection = Selection.Device(rule); refreshEditor() }
            drawRow = { graphics, rule, x, y, _ -> drawDeviceRow(graphics, rule, x, y) }
        },
    )

    // ------------------------------------------------------------------ widgets - P3 devices

    private val p3HeaderLine = add(TextLineWidget(0, 0, 0, GREY))

    private val zoneEnableButton = add(
        ActButtonWidget.builder(zoneEnableLabel()) { toggleZonesEnabled() }
            .width(90)
            .tooltip(Tooltip.create(Component.translatable("austrianpainter.zones.live_hint")))
            .build(),
    )

    private val zoneResetButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.zones.reset")) { resetZoneRules() }
            .width(110).build(),
    )

    private val p3List = add(
        RowListWidget<ZoneSourceRule>(0, 0, 0, 0, ROW_HEIGHT).apply {
            rows = BossZones.RULES.filter { !it.zone.p1 }
            isSelected = { (selection as? Selection.Zone)?.rule == it }
            onRowClick = { rule -> selection = Selection.Zone(rule); refreshEditor() }
            drawRow = { graphics, rule, x, y, _ -> drawZoneRow(graphics, rule, x, y, showZoneTag = true) }
        },
    )

    // ------------------------------------------------------------------ shared editor row

    private val editingLine = add(TextLineWidget(0, 0, 0, YELLOW))

    private val donorButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.device.pick_donor")) { pickDonor() }
            .width(120).build(),
    )

    private val paletteButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.device.use_palette")) { cyclePalette() }
            .width(120).build(),
    )

    private val clearButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.device.clear_rule")) { clearRule() }
            .width(110).build(),
    )

    /** Always active - unlike the three buttons above it does not act on [selection], it opens
     *  the per-boss "Replace Fluid" override screen for whichever preset is currently active. */
    private val replaceFluidButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.settings.replace_fluid")) {
            Minecraft.getInstance().setScreenAndShow(ReplaceFluidBossScreen(screen))
        }.width(140).build(),
    )

    init {
        refreshEditor()
    }

    // ------------------------------------------------------------------ layout

    override fun layout(area: ScreenRectangle) {
        val x = area.left() + MARGIN
        val contentWidth = area.width() - MARGIN * 2
        val columnWidth = (contentWidth - GAP * 2) / 3
        val p1X = x
        val p2X = x + columnWidth + GAP
        val p3X = x + (columnWidth + GAP) * 2

        val top = area.top() + GAP
        val editorRowY = area.bottom() - BUTTON_HEIGHT - GAP
        val editingLineY = editorRowY - TextLineWidget.HEIGHT - GAP
        val listBottom = editingLineY - GAP

        layoutColumn(p1X, columnWidth, top, listBottom, p1HeaderLine, conveyorEnableButton, conveyorResetButton, p1Card, p1List)
        layoutColumn(p2X, columnWidth, top, listBottom, p2HeaderLine, deviceEnableButton, deviceResetButton, p2Card, p2List)
        layoutColumn(p3X, columnWidth, top, listBottom, p3HeaderLine, zoneEnableButton, zoneResetButton, p3Card, p3List)

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
        // Right-aligned rather than chained after clearButton: it does not act on a selection,
        // so it reads as a separate control rather than a fourth step in the same row.
        replaceFluidButton.setRectangle(
            replaceFluidButton.width,
            BUTTON_HEIGHT,
            x + contentWidth - replaceFluidButton.width,
            editorRowY,
        )
    }

    /** One phase's column: header line, Live + Reset, then its card and list fill the rest. All
     *  three phases share this exact shape now that P2's old seed button is gone. */
    private fun layoutColumn(
        x: Int,
        width: Int,
        top: Int,
        listBottom: Int,
        headerLine: TextLineWidget,
        enableButton: ActButtonWidget,
        resetButton: ActButtonWidget,
        card: CardWidget,
        list: RowListWidget<*>,
    ) {
        var y = top
        headerLine.setRectangle(width, TextLineWidget.HEIGHT, x, y)
        y += TextLineWidget.HEIGHT + GAP

        enableButton.setRectangle(enableButton.width, BUTTON_HEIGHT, x, y)
        y += BUTTON_HEIGHT + GAP

        resetButton.setRectangle(resetButton.width, BUTTON_HEIGHT, x, y)
        y += BUTTON_HEIGHT + GAP * 2

        val listHeight = (listBottom - y).coerceAtLeast(ROW_HEIGHT)
        // Each list sits in a titled card, as the shell draws them; the list itself is inset inside.
        card.setRectangle(width, listHeight, x, y)
        val listTop = y + CardWidget.HEADER_HEIGHT
        val innerHeight = (listHeight - CardWidget.HEADER_HEIGHT - CardWidget.PAD).coerceAtLeast(ROW_HEIGHT)
        list.setRectangle(width - CardWidget.PAD * 2, innerHeight, x + CardWidget.PAD, listTop)
    }

    // ------------------------------------------------------------------ mutation

    private fun toggleConveyorEnabled() {
        ApSettings.conveyorEnabled = !ApSettings.conveyorEnabled
        ApSettings.save()
        conveyorEnableButton.message = conveyorEnableLabel()
        p1HeaderLine.message = conveyorHeaderText()
        dirty = true
    }

    private fun resetConveyorRules() {
        ApSettings.resetConveyorRules()
        dirty = true
        refreshEditor()
    }

    private fun toggleDeviceEnabled() {
        ApSettings.deviceEnabled = !ApSettings.deviceEnabled
        ApSettings.save()
        deviceEnableButton.message = deviceEnableLabel()
        p2HeaderLine.message = deviceHeaderText()
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
        p3HeaderLine.message = zoneHeaderText()
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
        p1HeaderLine.message = conveyorHeaderText()
        p2HeaderLine.message = deviceHeaderText()
        p3HeaderLine.message = zoneHeaderText()
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

    private fun conveyorEnableLabel(): Component = Component.translatable(
        if (ApSettings.conveyorEnabled) "austrianpainter.conveyor.on" else "austrianpainter.conveyor.off",
    )

    private fun deviceEnableLabel(): Component = Component.translatable(
        if (ApSettings.deviceEnabled) "austrianpainter.device.on" else "austrianpainter.device.off",
    )

    private fun zoneEnableLabel(): Component = Component.translatable(
        if (ApSettings.zonesEnabled) "austrianpainter.zones.on" else "austrianpainter.zones.off",
    )

    private fun conveyorHeaderText(): Component = Component.translatable(
        "austrianpainter.dungeon.device_header",
        if (ApSettings.conveyorEnabled && BossZones.shouldApply()) {
            Component.translatable("austrianpainter.zones.active")
        } else {
            Component.translatable("austrianpainter.zones.inactive")
        },
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
        "austrianpainter.dungeon.device_header",
        if (ApSettings.zonesEnabled && BossZones.shouldApply()) {
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
        BossZone.CRUSHER -> ZONE_CRUSHER
        BossZone.CRUSHER_P1 -> ZONE_CRUSHER
    }

    // ------------------------------------------------------------------ row rendering

    /**
     * The coloured bar identifying which array or zone a rule belongs to.
     *
     * Inset from the row's top and bottom rather than filling its full height: run together, the
     * bars of consecutive rules in the same array read as one continuous stripe, and the point of
     * the colour is to let you count the rules in a group at a glance.
     */
    private fun drawAccent(graphics: GuiGraphicsExtractor, x: Int, y: Int, colour: Int) {
        nvgSurface(
            graphics, x, y, ACCENT_WIDTH, ROW_HEIGHT,
            nvg = {
                NVGUtils.drawRect(
                    x.toFloat(),
                    (y + ACCENT_INSET).toFloat(),
                    ACCENT_WIDTH.toFloat(),
                    (ROW_HEIGHT - ACCENT_INSET * 2).toFloat(),
                    ACCENT_RADIUS,
                    Theme.c(colour),
                )
            },
            vanilla = { graphics.fill(x, y + ACCENT_INSET, x + ACCENT_WIDTH, y + ROW_HEIGHT - ACCENT_INSET, colour) },
        )
    }

    private fun drawDeviceRow(
        graphics: GuiGraphicsExtractor,
        rule: DeviceRule,
        x: Int,
        y: Int,
    ) {
        RowContent.accent(graphics, x, y, ROW_HEIGHT, deviceAccent(rule))

        val target = ApSettings.deviceRule(rule.array, rule.source)
        val labelX = RowContent.pair(
            graphics, x, y, ROW_HEIGHT,
            rule.source.block,
            (target as? AreaTarget.Donor)?.block,
        )

        // The array is the row's grouping, so it rides at the right edge as the trailing readout and
        // leaves the label to say what the rule actually does.
        RowContent.label(
            graphics,
            labelX,
            y,
            ROW_HEIGHT,
            x + p2List.width,
            text = target?.displayName() ?: Component.translatable("austrianpainter.device.left_alone"),
            trailing = arrayName(rule),
            colour = if (target == null) Theme.MUTED else Theme.INK,
        )
    }

    /**
     * Shared by P1 and P3 - both are plain [ZoneSourceRule] lists, only which zones they hold
     * differs. [showZoneTag] drops the trailing zone-name readout for P1: every one of its rows is
     * already the same zone (Conveyer), so repeating it on every row would just be noise the card's
     * own header already says once.
     */
    private fun drawZoneRow(
        graphics: GuiGraphicsExtractor,
        rule: ZoneSourceRule,
        x: Int,
        y: Int,
        showZoneTag: Boolean,
    ) {
        RowContent.accent(graphics, x, y, ROW_HEIGHT, zoneAccent(rule.zone))

        val target = ApSettings.zoneRule(rule)
        val labelX = RowContent.pair(
            graphics, x, y, ROW_HEIGHT,
            rule.block,
            (target as? AreaTarget.Donor)?.block,
        )

        val list = if (rule.zone.p1) p1List else p3List
        RowContent.label(
            graphics,
            labelX,
            y,
            ROW_HEIGHT,
            x + list.width,
            text = target?.displayName() ?: Component.translatable("austrianpainter.device.left_alone"),
            trailing = if (showZoneTag) zoneName(rule) else null,
            colour = if (target == null) Theme.MUTED else Theme.INK,
        )
    }
}
