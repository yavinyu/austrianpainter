package com.maxisch.client.gui.tab

import com.maxisch.client.gui.PainterScreen
import com.maxisch.client.gui.widget.ColorSwatchWidget
import com.maxisch.client.gui.widget.PanelWidget
import com.maxisch.client.gui.widget.StepperWidget
import com.maxisch.client.gui.widget.TextLineWidget
import com.maxisch.client.gui.widget.ToggleSwitchWidget
import com.maxisch.client.render.BlockHighlight
import com.maxisch.client.render.PaintedOverlay
import com.maxisch.dungeon.RoomTracker
import com.maxisch.paint.ApSettings
import com.maxisch.paint.PaintStorage
import com.maxisch.paint.PresetKind
import com.maxisch.paint.PresetStore
import com.maxisch.paint.PresetStores
import com.maxisch.paint.session.PaintBrush
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.network.chat.Component

/**
 * Every option the YACL settings screen offers, rebuilt as hand-rolled widgets so it can live as a
 * tab instead of its own screen. Laid out as four drawn panels rather than a uniform grid: a wide
 * Colours band carries this tab's one deliberately prominent element (this is a painting mod, so
 * colour editing gets more room and a live self-coloured accent bar instead of being boxed into a
 * column the same size as everything else), General sits beside it, Overlay and Dungeon share a row
 * below, and Presets is a full-width strip at the bottom.
 *
 * Every control here applies immediately (`ApSettings.<field> = x; ApSettings.save()`), including
 * the active-preset quick-swap cyclers - unlike the old YACL screen, which deferred preset swaps to
 * a Save step specifically because activating a preset rebuilds every loaded chunk. This tab has no
 * Save/Cancel gate at all, so a deferred preset swap would be the one inconsistent control on it;
 * the [PresetsTab] quick-swap cycler this mirrors already applies immediately with no confirmation.
 *
 * The old YACL screen ([com.maxisch.client.gui.ApSettingsScreen]) is untouched and stays reachable
 * from ModMenu's config button - this tab is the primary surface, not a replacement for it.
 */
class SettingsTab(private val screen: PainterScreen) : ApTab("austrianpainter.tab.settings") {

    private companion object {
        const val MARGIN = 8
        const val GAP = 6
        const val PAD = 6
        const val ROW = 20
        const val ROW_GAP = 6
        const val TOGGLE_W = 30
        const val TOGGLE_H = 14
        const val SWATCH_W = 26
        const val SWATCH_H = 22
        const val OVERLAY_RADIUS_STEP = 2
        const val GREY = 0xFFA0A0A0.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()

        /** Matches the boss-dungeon purple [DungeonTab] already uses for its device-array accent. */
        const val DUNGEON_ACCENT = 0xFFAA00AA.toInt()
    }

    private val font get() = Minecraft.getInstance().font

    // ------------------------------------------------------------------ panels (added first - render order is add() order, and these must sit underneath everything else)

    private val coloursPanel = add(PanelWidget(0, 0, 0, 0, ApSettings.areaOutlineColor))
    private val generalPanel = add(PanelWidget(0, 0, 0, 0, GREY))
    private val overlayPanel = add(PanelWidget(0, 0, 0, 0, ApSettings.paintedOverlayColor))
    private val dungeonPanel = add(PanelWidget(0, 0, 0, 0, DUNGEON_ACCENT))
    private val presetsPanel = add(PanelWidget(0, 0, 0, 0, GREY))

    // ------------------------------------------------------------------ headers

    private val coloursHeader = add(TextLineWidget(0, 0, 0, GREY))
    private val generalHeader = add(TextLineWidget(0, 0, 0, GREY))
    private val overlayHeader = add(TextLineWidget(0, 0, 0, GREY))
    private val dungeonHeader = add(TextLineWidget(0, 0, 0, GREY))
    private val presetsHeader = add(TextLineWidget(0, 0, 0, GREY))

    init {
        coloursHeader.message = Component.translatable("austrianpainter.settings.area")
        generalHeader.message = Component.translatable("austrianpainter.settings.general")
        overlayHeader.message = Component.translatable("austrianpainter.settings.overlay")
        dungeonHeader.message = Component.translatable("austrianpainter.settings.dungeon")
        presetsHeader.message = Component.translatable("austrianpainter.settings.presets")
    }

    // ------------------------------------------------------------------ colours band

    private val outlineLabel = add(TextLineWidget(0, 0, 0, WHITE)).also {
        it.message = Component.translatable("austrianpainter.settings.area_outline")
    }
    private val outlineBox: EditBox
    private val outlineSwatch: ColorSwatchWidget

    private val fillLabel = add(TextLineWidget(0, 0, 0, WHITE)).also {
        it.message = Component.translatable("austrianpainter.settings.area_fill")
    }
    private val fillBox: EditBox
    private val fillSwatch: ColorSwatchWidget

    private val previewLabel = add(TextLineWidget(0, 0, 0, WHITE)).also {
        it.message = Component.translatable("austrianpainter.settings.preview_outline")
    }
    private val previewBox: EditBox
    private val previewSwatch: ColorSwatchWidget

    private val overlayColorLabel = add(TextLineWidget(0, 0, 0, WHITE)).also {
        it.message = Component.translatable("austrianpainter.settings.overlay_color")
    }
    private val overlayColorBox: EditBox
    private val overlayColorSwatch: ColorSwatchWidget

    init {
        val (ob, os) = colorRow({ ApSettings.areaOutlineColor }, { ApSettings.areaOutlineColor = it }) {
            coloursPanel.accent = it
        }
        outlineBox = ob
        outlineSwatch = os
        val (fb, fs) = colorRow({ ApSettings.areaFillColor }, { ApSettings.areaFillColor = it })
        fillBox = fb
        fillSwatch = fs
        val (pb, ps) = colorRow({ ApSettings.areaPreviewColor }, { ApSettings.areaPreviewColor = it })
        previewBox = pb
        previewSwatch = ps
        val (ocb, ocs) = colorRow({ ApSettings.paintedOverlayColor }, { ApSettings.paintedOverlayColor = it }) {
            PaintedOverlay.invalidate()
            overlayPanel.accent = it
        }
        overlayColorBox = ocb
        overlayColorSwatch = ocs
    }

    /**
     * Builds and registers one hex-entry + swatch pair; [onChanged] runs after every valid edit and
     * receives the parsed ARGB value, so a panel's own accent bar can track a colour it controls live.
     */
    private fun colorRow(get: () -> Int, set: (Int) -> Unit, onChanged: (Int) -> Unit = {}): Pair<EditBox, ColorSwatchWidget> {
        val box = add(
            EditBox(font, 0, 0, 90, 18, Component.translatable("austrianpainter.settings.hex_hint")).apply {
                setMaxLength(9)
                setHint(Component.translatable("austrianpainter.settings.hex_hint"))
                value = "%08X".format(get())
            },
        )
        val swatch = add(ColorSwatchWidget(0, 0, 16, 18, get()))
        box.setResponder { text ->
            val argb = text.trim().removePrefix("#").toUIntOrNull(16)?.toInt() ?: return@setResponder
            set(argb)
            ApSettings.save()
            swatch.color = argb
            onChanged(argb)
        }
        return box to swatch
    }

    // ------------------------------------------------------------------ general panel

    private val hudToggle = add(ToggleSwitchWidget(0, 0, 0, 0, ApSettings.showHud, GREY) { toggleHud() })
    private val hudLabelLine = add(TextLineWidget(0, 0, 0, WHITE)).also {
        it.message = Component.translatable("austrianpainter.settings.show_hud")
    }

    private val hintsToggle = add(ToggleSwitchWidget(0, 0, 0, 0, ApSettings.showHints, GREY) { toggleHints() })
    private val hintsLabelLine = add(TextLineWidget(0, 0, 0, WHITE)).also {
        it.message = Component.translatable("austrianpainter.settings.show_hints")
    }

    private val lookingAtToggle =
        add(ToggleSwitchWidget(0, 0, 0, 0, ApSettings.showLookingAt, GREY) { toggleLookingAt() })
    private val lookingAtLabelLine = add(TextLineWidget(0, 0, 0, WHITE)).also {
        it.message = Component.translatable("austrianpainter.settings.show_looking_at")
    }

    private val soundToggle = add(ToggleSwitchWidget(0, 0, 0, 0, ApSettings.paintSound, GREY) { toggleSound() })
    private val soundLabelLine = add(TextLineWidget(0, 0, 0, WHITE)).also {
        it.message = Component.translatable("austrianpainter.settings.paint_sound")
    }

    private val keybindsToggle =
        add(ToggleSwitchWidget(0, 0, 0, 0, ApSettings.keybindsEnabled, GREY) { toggleKeybinds() })
    private val keybindsLabelLine = add(TextLineWidget(0, 0, 0, WHITE)).also {
        it.message = Component.translatable("austrianpainter.settings.keybinds_enabled")
    }

    private val brushLabel = add(TextLineWidget(0, 0, 0, GREY)).also {
        it.message = Component.translatable("austrianpainter.settings.brush_radius")
    }
    private val brushStepper = add(
        StepperWidget(
            0, 0, 0, 0,
            value = PaintBrush.radius,
            min = PaintBrush.MIN_RADIUS,
            max = PaintBrush.MAX_RADIUS,
            step = 1,
            format = { Component.translatable("austrianpainter.brush.size", it) },
        ) { adjustBrushRadius(it) },
    ).also {
        it.setTooltip(
            Tooltip.create(
                Component.translatable("austrianpainter.settings.brush_radius.desc", 2 * PaintBrush.MAX_RADIUS - 1),
            ),
        )
    }

    // ------------------------------------------------------------------ overlay panel

    private val overlayToggle =
        add(ToggleSwitchWidget(0, 0, 0, 0, ApSettings.showPaintedOverlay, ApSettings.paintedOverlayColor) { toggleOverlay() })
    private val overlayLabelLine = add(TextLineWidget(0, 0, 0, WHITE)).also {
        it.message = Component.translatable("austrianpainter.settings.overlay_show")
    }

    private val overlayRadiusStepper = add(
        StepperWidget(
            0, 0, 0, 0,
            value = ApSettings.paintedOverlayRadius,
            min = ApSettings.MIN_OVERLAY_RADIUS,
            max = ApSettings.MAX_OVERLAY_RADIUS,
            step = OVERLAY_RADIUS_STEP,
            format = { Component.translatable("austrianpainter.settings.overlay_radius_value", it) },
        ) { adjustOverlayRadius(it) },
    ).also {
        it.setTooltip(
            Tooltip.create(Component.translatable("austrianpainter.settings.overlay_radius.desc", BlockHighlight.MAX_BOXES)),
        )
    }

    // ------------------------------------------------------------------ dungeon panel

    private val roomScopeToggle =
        add(ToggleSwitchWidget(0, 0, 0, 0, ApSettings.dungeonRoomScope, DUNGEON_ACCENT) { toggleRoomScope() })
    private val roomScopeLabelLine = add(TextLineWidget(0, 0, 0, WHITE)).also {
        it.message = Component.translatable("austrianpainter.settings.room_scope")
    }

    private val rescanButton = add(
        Button.builder(Component.translatable("austrianpainter.settings.rescan")) { RoomTracker.reset() }
            .width(1)
            .tooltip(Tooltip.create(Component.translatable("austrianpainter.settings.rescan.desc")))
            .build(),
    )

    private val unbindTypesButton = add(
        Button.builder(Component.translatable("austrianpainter.settings.unbind_room_types")) {
            PaintStorage.scope?.key?.let { ApSettings.bindRoomTypes(it, null) }
        }
            .width(1)
            .tooltip(Tooltip.create(Component.translatable("austrianpainter.settings.unbind_room_types.desc")))
            .build(),
    )

    private val unbindBlocksButton = add(
        Button.builder(Component.translatable("austrianpainter.settings.unbind_room_blocks")) {
            PaintStorage.scope?.let {
                if (it.isBoss) ApSettings.bindBossPreset(it.key, null) else ApSettings.bindRoomPreset(it.key, null)
            }
        }
            .width(1)
            .tooltip(Tooltip.create(Component.translatable("austrianpainter.settings.unbind_room_blocks.desc")))
            .build(),
    )

    private val unbindPalettesButton = add(
        Button.builder(Component.translatable("austrianpainter.settings.unbind_room_palettes")) {
            PaintStorage.scope?.key?.let { ApSettings.bindRoomPalettes(it, null) }
        }
            .width(1)
            .tooltip(Tooltip.create(Component.translatable("austrianpainter.settings.unbind_room_palettes.desc")))
            .build(),
    )

    // ------------------------------------------------------------------ presets strip

    private val blocksLabel = add(TextLineWidget(0, 0, 0, GREY)).also {
        it.message = Component.translatable("austrianpainter.presets.blocks")
    }
    private val blocksPrev = add(Button.builder(Component.translatable("austrianpainter.presets.prev")) { stepPreset(PresetStores.blocks, PaintStorage::activateBlockPreset, -1) }.width(20).build())
    private val blocksNameLine = add(TextLineWidget(0, 0, 0, WHITE))
    private val blocksNext = add(Button.builder(Component.translatable("austrianpainter.presets.next")) { stepPreset(PresetStores.blocks, PaintStorage::activateBlockPreset, 1) }.width(20).build())
    private val blocksManage = add(
        Button.builder(Component.translatable("austrianpainter.settings.manage_blocks")) {
            screen.switchToPresets(PresetKind.BLOCKS)
        }.width(150).build(),
    )

    private val typesLabel = add(TextLineWidget(0, 0, 0, GREY)).also {
        it.message = Component.translatable("austrianpainter.presets.types")
    }
    private val typesPrev = add(Button.builder(Component.translatable("austrianpainter.presets.prev")) { stepPreset(PresetStores.types, PaintStorage::activateTypePreset, -1) }.width(20).build())
    private val typesNameLine = add(TextLineWidget(0, 0, 0, WHITE))
    private val typesNext = add(Button.builder(Component.translatable("austrianpainter.presets.next")) { stepPreset(PresetStores.types, PaintStorage::activateTypePreset, 1) }.width(20).build())
    private val typesManage = add(
        Button.builder(Component.translatable("austrianpainter.settings.manage_types")) {
            screen.switchToPresets(PresetKind.TYPES)
        }.width(150).build(),
    )

    private val palettesLabel = add(TextLineWidget(0, 0, 0, GREY)).also {
        it.message = Component.translatable("austrianpainter.presets.palettes")
    }
    private val palettesPrev = add(Button.builder(Component.translatable("austrianpainter.presets.prev")) { stepPreset(PresetStores.palettes, PaintStorage::activatePalette, -1) }.width(20).build())
    private val palettesNameLine = add(TextLineWidget(0, 0, 0, WHITE))
    private val palettesNext = add(Button.builder(Component.translatable("austrianpainter.presets.next")) { stepPreset(PresetStores.palettes, PaintStorage::activatePalette, 1) }.width(20).build())
    private val palettesManage = add(
        Button.builder(Component.translatable("austrianpainter.settings.manage_palettes")) {
            screen.switchToPresets(PresetKind.PALETTES)
        }.width(150).build(),
    )

    /** Must run after every widget field above is initialised - [refresh] reads all of them. */
    init {
        refresh()
    }

    // ------------------------------------------------------------------ layout

    override fun doLayout(area: ScreenRectangle) {
        val x = area.left() + MARGIN
        val contentWidth = area.width() - MARGIN * 2
        val top = area.top() + GAP

        val coloursWidth = (contentWidth - GAP) * 3 / 5
        val generalWidth = contentWidth - GAP - coloursWidth
        val generalX = x + coloursWidth + GAP

        // -------------------------------------------------- colours panel content
        var cy = top + PAD
        val coloursInner = coloursWidth - PAD * 2
        coloursHeader.setRectangle(coloursInner, TextLineWidget.HEIGHT, x + PAD, cy)
        cy += TextLineWidget.HEIGHT + ROW_GAP
        val boxWidth = (coloursInner - SWATCH_W - 4).coerceAtLeast(40)
        for ((label, box, swatch) in listOf(
            Triple(outlineLabel, outlineBox, outlineSwatch),
            Triple(fillLabel, fillBox, fillSwatch),
            Triple(previewLabel, previewBox, previewSwatch),
            Triple(overlayColorLabel, overlayColorBox, overlayColorSwatch),
        )) {
            label.setRectangle(coloursInner, TextLineWidget.HEIGHT, x + PAD, cy)
            cy += TextLineWidget.HEIGHT + 3
            box.setRectangle(boxWidth, SWATCH_H, x + PAD, cy)
            swatch.setRectangle(SWATCH_W, SWATCH_H, x + PAD + boxWidth + 4, cy)
            cy += SWATCH_H + ROW_GAP
        }
        val coloursContentBottom = cy - ROW_GAP + PAD

        // -------------------------------------------------- general panel content
        var gy = top + PAD
        val generalInner = generalWidth - PAD * 2
        generalHeader.setRectangle(generalInner, TextLineWidget.HEIGHT, generalX + PAD, gy)
        gy += TextLineWidget.HEIGHT + ROW_GAP
        for ((toggle, label) in listOf(
            hudToggle to hudLabelLine,
            hintsToggle to hintsLabelLine,
            lookingAtToggle to lookingAtLabelLine,
            soundToggle to soundLabelLine,
            keybindsToggle to keybindsLabelLine,
        )) {
            toggle.setRectangle(TOGGLE_W, TOGGLE_H, generalX + PAD, gy + 3)
            label.setRectangle(
                (generalInner - TOGGLE_W - 4).coerceAtLeast(20),
                TextLineWidget.HEIGHT,
                generalX + PAD + TOGGLE_W + 4,
                gy + 5,
            )
            gy += ROW
        }
        gy += ROW_GAP - 2
        brushLabel.setRectangle(generalInner, TextLineWidget.HEIGHT, generalX + PAD, gy)
        gy += TextLineWidget.HEIGHT + 3
        brushStepper.setRectangle(generalInner, ROW, generalX + PAD, gy)
        gy += ROW
        val generalContentBottom = gy + PAD

        val topBandBottom = maxOf(coloursContentBottom, generalContentBottom)
        coloursPanel.setRectangle(coloursWidth, topBandBottom - top, x, top)
        generalPanel.setRectangle(generalWidth, topBandBottom - top, generalX, top)

        // -------------------------------------------------- overlay + dungeon panels
        val bottomTop = topBandBottom + GAP
        val halfWidth = (contentWidth - GAP) / 2
        val rightWidth = contentWidth - halfWidth - GAP
        val dungeonX = x + halfWidth + GAP

        var oy = bottomTop + PAD
        val overlayInner = halfWidth - PAD * 2
        overlayHeader.setRectangle(overlayInner, TextLineWidget.HEIGHT, x + PAD, oy)
        oy += TextLineWidget.HEIGHT + ROW_GAP
        overlayToggle.setRectangle(TOGGLE_W, TOGGLE_H, x + PAD, oy + 3)
        overlayLabelLine.setRectangle(
            (overlayInner - TOGGLE_W - 4).coerceAtLeast(20),
            TextLineWidget.HEIGHT,
            x + PAD + TOGGLE_W + 4,
            oy + 5,
        )
        oy += ROW + ROW_GAP
        overlayRadiusStepper.setRectangle(overlayInner, ROW, x + PAD, oy)
        oy += ROW
        val overlayContentBottom = oy + PAD

        var dy = bottomTop + PAD
        val dungeonInner = rightWidth - PAD * 2
        dungeonHeader.setRectangle(dungeonInner, TextLineWidget.HEIGHT, dungeonX + PAD, dy)
        dy += TextLineWidget.HEIGHT + ROW_GAP
        roomScopeToggle.setRectangle(TOGGLE_W, TOGGLE_H, dungeonX + PAD, dy + 3)
        roomScopeLabelLine.setRectangle(
            (dungeonInner - TOGGLE_W - 4).coerceAtLeast(20),
            TextLineWidget.HEIGHT,
            dungeonX + PAD + TOGGLE_W + 4,
            dy + 5,
        )
        dy += ROW + ROW_GAP
        for (button in listOf(rescanButton, unbindTypesButton, unbindBlocksButton, unbindPalettesButton)) {
            button.setRectangle(dungeonInner, ROW, dungeonX + PAD, dy)
            dy += ROW + 4
        }
        val dungeonContentBottom = dy - 4 + PAD

        val bottomBandBottom = maxOf(overlayContentBottom, dungeonContentBottom)
        overlayPanel.setRectangle(halfWidth, bottomBandBottom - bottomTop, x, bottomTop)
        dungeonPanel.setRectangle(rightWidth, bottomBandBottom - bottomTop, dungeonX, bottomTop)

        // -------------------------------------------------- presets strip, full width below
        val presetsTop = bottomBandBottom + GAP
        var py = presetsTop + PAD
        presetsHeader.setRectangle(contentWidth - PAD * 2, TextLineWidget.HEIGHT, x + PAD, py)
        py += TextLineWidget.HEIGHT + ROW_GAP

        val labelWidth = 60
        val nameWidth = (contentWidth - PAD * 2 - labelWidth - 20 * 2 - 150 - GAP * 4).coerceAtLeast(40)
        for ((label, prev, nameLine, next, manage) in listOf(
            PresetRow(blocksLabel, blocksPrev, blocksNameLine, blocksNext, blocksManage),
            PresetRow(typesLabel, typesPrev, typesNameLine, typesNext, typesManage),
            PresetRow(palettesLabel, palettesPrev, palettesNameLine, palettesNext, palettesManage),
        )) {
            var px = x + PAD
            label.setRectangle(labelWidth, TextLineWidget.HEIGHT, px, py + 5)
            px += labelWidth
            prev.setRectangle(20, ROW, px, py)
            px += 20 + GAP
            nameLine.setRectangle(nameWidth, TextLineWidget.HEIGHT, px, py + 5)
            px += nameWidth + GAP
            next.setRectangle(20, ROW, px, py)
            px += 20 + GAP
            manage.setRectangle(150, ROW, px, py)
            py += ROW + ROW_GAP
        }
        val presetsBottom = py - ROW_GAP + PAD
        presetsPanel.setRectangle(contentWidth, presetsBottom - presetsTop, x, presetsTop)
    }

    private data class PresetRow(
        val label: TextLineWidget,
        val prev: Button,
        val nameLine: TextLineWidget,
        val next: Button,
        val manage: Button,
    )

    // ------------------------------------------------------------------ mutation

    private fun toggleHud() {
        ApSettings.showHud = !ApSettings.showHud
        ApSettings.save()
        refresh()
    }

    private fun toggleHints() {
        ApSettings.showHints = !ApSettings.showHints
        ApSettings.save()
        refresh()
    }

    private fun toggleLookingAt() {
        ApSettings.showLookingAt = !ApSettings.showLookingAt
        ApSettings.save()
        refresh()
    }

    private fun toggleSound() {
        ApSettings.paintSound = !ApSettings.paintSound
        ApSettings.save()
        refresh()
    }

    private fun toggleKeybinds() {
        ApSettings.keybindsEnabled = !ApSettings.keybindsEnabled
        ApSettings.save()
        refresh()
    }

    private fun adjustBrushRadius(newValue: Int) {
        PaintBrush.radius = newValue.coerceIn(PaintBrush.MIN_RADIUS, PaintBrush.MAX_RADIUS)
        ApSettings.save()
        refresh()
    }

    private fun toggleOverlay() {
        ApSettings.showPaintedOverlay = !ApSettings.showPaintedOverlay
        ApSettings.save()
        PaintedOverlay.invalidate()
        refresh()
    }

    private fun adjustOverlayRadius(newValue: Int) {
        ApSettings.paintedOverlayRadius = newValue.coerceIn(ApSettings.MIN_OVERLAY_RADIUS, ApSettings.MAX_OVERLAY_RADIUS)
        ApSettings.save()
        PaintedOverlay.invalidate()
        refresh()
    }

    private fun toggleRoomScope() {
        ApSettings.dungeonRoomScope = !ApSettings.dungeonRoomScope
        ApSettings.save()
        refresh()
    }

    private fun stepPreset(store: PresetStore<*>, activate: (String) -> Unit, delta: Int) {
        val names = store.listWithActive()
        if (names.isEmpty()) return
        val index = names.indexOf(store.activeName)
        activate(names[(index + delta).mod(names.size)])
        refresh()
    }

    // ------------------------------------------------------------------ state

    override fun refresh() {
        hudToggle.checked = ApSettings.showHud
        hintsToggle.checked = ApSettings.showHints
        lookingAtToggle.checked = ApSettings.showLookingAt
        soundToggle.checked = ApSettings.paintSound
        keybindsToggle.checked = ApSettings.keybindsEnabled

        brushStepper.value = PaintBrush.radius

        overlayToggle.checked = ApSettings.showPaintedOverlay
        overlayToggle.accent = ApSettings.paintedOverlayColor
        overlayPanel.accent = ApSettings.paintedOverlayColor
        overlayRadiusStepper.value = ApSettings.paintedOverlayRadius

        roomScopeToggle.checked = ApSettings.dungeonRoomScope

        coloursPanel.accent = ApSettings.areaOutlineColor

        blocksNameLine.message = Component.literal(PresetStores.blocks.activeName)
        typesNameLine.message = Component.literal(PresetStores.types.activeName)
        palettesNameLine.message = Component.literal(PresetStores.palettes.activeName)
    }
}
