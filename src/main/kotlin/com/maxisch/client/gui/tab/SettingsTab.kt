package com.maxisch.client.gui.tab

import com.maxisch.client.gui.ColorPickerScreen
import com.maxisch.client.gui.PainterScreen
import com.maxisch.client.gui.Theme
import com.maxisch.client.gui.widget.ActButtonWidget
import com.maxisch.client.gui.widget.ColorSwatchWidget
import com.maxisch.client.gui.widget.CardWidget
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
import com.maxisch.client.gui.widget.ApEditBox
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.network.chat.Component

/**
 * Every option the old YACL settings screen used to offer, rebuilt as hand-rolled widgets so it can
 * live as a tab instead of its own screen. Laid out as four drawn panels rather than a uniform grid: a wide
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
 * The YACL screen this replaced is gone, along with the ModMenu entry point that opened it; this
 * tab is the only settings surface now.
 */
class SettingsTab(private val screen: PainterScreen) : ApTab("austrianpainter.tab.settings") {

    private companion object {
        const val MARGIN = 8
        const val GAP = 5
        const val PAD = 5

        /**
         * This tab lays out five stacked panels and is the tallest in the mod - tall enough that it
         * used to run past the bottom of its area even before the frame took a slice. These are the
         * tightest values that keep a 14px toggle and a 20px button from touching their neighbours.
         */
        const val ROW = 18
        const val ROW_GAP = 4
        const val TOGGLE_W = 30
        const val TOGGLE_H = 14
        const val SWATCH_W = 26
        const val SWATCH_H = 22
        const val OVERLAY_RADIUS_STEP = 2
        const val GREY = 0xFFA0A0A0.toInt()

        /** Marks a hex field whose text cannot be parsed. */
        val RED = Theme.RED
        const val WHITE = 0xFFFFFFFF.toInt()
    }

    private val font get() = Minecraft.getInstance().font

    // ------------------------------------------------------------------ panels (added first - render order is add() order, and these must sit underneath everything else)

    private val coloursPanel = add(CardWidget(Component.translatable("austrianpainter.settings.area")))
    private val generalPanel = add(CardWidget(Component.translatable("austrianpainter.settings.general")))
    private val overlayPanel = add(CardWidget(Component.translatable("austrianpainter.settings.overlay")))
    private val dungeonPanel = add(CardWidget(Component.translatable("austrianpainter.settings.dungeon")))
    private val presetsPanel = add(CardWidget(Component.translatable("austrianpainter.settings.presets")))

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
        val (ob, os) = colorRow({ ApSettings.areaOutlineColor }, { ApSettings.areaOutlineColor = it })
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
            ApEditBox(font, 0, 0, 90, 18, Component.translatable("austrianpainter.settings.hex_hint")).apply {
                setMaxLength(9)
                setHint(Component.translatable("austrianpainter.settings.hex_hint"))
                value = "%08X".format(get())
            },
        )
        val swatch = add(ColorSwatchWidget(0, 0, 16, 18, get()))

        /** Applies a colour everywhere it is mirrored, and persists it once. */
        fun commit(argb: Int) {
            set(argb)
            ApSettings.save()
            box.value = "%08X".format(argb)
            swatch.color = argb
            onChanged(argb)
        }

        box.setResponder { text ->
            val argb = text.trim().removePrefix("#").toUIntOrNull(16)?.toInt()
            // Colour the field rather than failing silently, and do not touch disk on every
            // keystroke: eight digits used to mean up to eight full JSON writes. The value lands
            // when the box loses focus or the picker commits.
            box.setTextColor(if (argb == null) RED else WHITE)
            if (argb != null) {
                set(argb)
                swatch.color = argb
                onChanged(argb)
            }
        }

        swatch.onPress = {
            Minecraft.getInstance().setScreenAndShow(
                ColorPickerScreen(screen, Component.translatable("austrianpainter.settings.pick_colour"), get()) {
                    commit(it)
                },
            )
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
        add(ToggleSwitchWidget(0, 0, 0, 0, ApSettings.showPaintedOverlay, GREY) { toggleOverlay() })
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
        add(ToggleSwitchWidget(0, 0, 0, 0, ApSettings.dungeonRoomScope, GREY) { toggleRoomScope() })
    private val roomScopeLabelLine = add(TextLineWidget(0, 0, 0, WHITE)).also {
        it.message = Component.translatable("austrianpainter.settings.room_scope")
    }

    private val rescanButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.settings.rescan")) { RoomTracker.reset() }
            .width(1)
            .tooltip(Tooltip.create(Component.translatable("austrianpainter.settings.rescan.desc")))
            .build(),
    )

    private val unbindTypesButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.settings.unbind_room_types")) {
            PaintStorage.scope?.key?.let { ApSettings.bindRoomTypes(it, null) }
        }
            .width(1)
            .tooltip(Tooltip.create(Component.translatable("austrianpainter.settings.unbind_room_types.desc")))
            .build(),
    )

    private val unbindBlocksButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.settings.unbind_room_blocks")) {
            PaintStorage.scope?.let {
                if (it.isBoss) ApSettings.bindBossPreset(it.key, null) else ApSettings.bindRoomPreset(it.key, null)
            }
        }
            .width(1)
            .tooltip(Tooltip.create(Component.translatable("austrianpainter.settings.unbind_room_blocks.desc")))
            .build(),
    )

    private val unbindPalettesButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.settings.unbind_room_palettes")) {
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
    private val blocksPrev = add(ActButtonWidget.builder(Component.translatable("austrianpainter.presets.prev")) { stepPreset(PresetStores.blocks, PaintStorage::activateBlockPreset, -1) }.width(20).build())
    private val blocksNameLine = add(TextLineWidget(0, 0, 0, WHITE))
    private val blocksNext = add(ActButtonWidget.builder(Component.translatable("austrianpainter.presets.next")) { stepPreset(PresetStores.blocks, PaintStorage::activateBlockPreset, 1) }.width(20).build())
    private val blocksManage = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.settings.manage_blocks")) {
            screen.switchToPresets(PresetKind.BLOCKS)
        }.width(150).build(),
    )

    private val typesLabel = add(TextLineWidget(0, 0, 0, GREY)).also {
        it.message = Component.translatable("austrianpainter.presets.types")
    }
    private val typesPrev = add(ActButtonWidget.builder(Component.translatable("austrianpainter.presets.prev")) { stepPreset(PresetStores.types, PaintStorage::activateTypePreset, -1) }.width(20).build())
    private val typesNameLine = add(TextLineWidget(0, 0, 0, WHITE))
    private val typesNext = add(ActButtonWidget.builder(Component.translatable("austrianpainter.presets.next")) { stepPreset(PresetStores.types, PaintStorage::activateTypePreset, 1) }.width(20).build())
    private val typesManage = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.settings.manage_types")) {
            screen.switchToPresets(PresetKind.TYPES)
        }.width(150).build(),
    )

    private val palettesLabel = add(TextLineWidget(0, 0, 0, GREY)).also {
        it.message = Component.translatable("austrianpainter.presets.palettes")
    }
    private val palettesPrev = add(ActButtonWidget.builder(Component.translatable("austrianpainter.presets.prev")) { stepPreset(PresetStores.palettes, PaintStorage::activatePalette, -1) }.width(20).build())
    private val palettesNameLine = add(TextLineWidget(0, 0, 0, WHITE))
    private val palettesNext = add(ActButtonWidget.builder(Component.translatable("austrianpainter.presets.next")) { stepPreset(PresetStores.palettes, PaintStorage::activatePalette, 1) }.width(20).build())
    private val palettesManage = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.settings.manage_palettes")) {
            screen.switchToPresets(PresetKind.PALETTES)
        }.width(150).build(),
    )

    /** Must run after every widget field above is initialised - [refresh] reads all of them. */
    init {
        refresh()
    }

    /**
     * Persists whatever the hex fields typed.
     *
     * They edit [ApSettings] live so the swatch and the in-world outline track the text, but they no
     * longer write to disk per keystroke - this is where those edits land. Every other control here
     * saves as it goes, so a redundant save costs one JSON write on leaving the tab.
     */
    override fun onHidden() {
        ApSettings.save()
    }

    // ------------------------------------------------------------------ layout

    override fun layout(area: ScreenRectangle) {
        val x = area.left() + MARGIN
        val contentWidth = area.width() - MARGIN * 2
        val top = area.top() + GAP

        val coloursWidth = (contentWidth - GAP) * 3 / 5
        val generalWidth = contentWidth - GAP - coloursWidth
        val generalX = x + coloursWidth + GAP

        // -------------------------------------------------- colours panel content
        var cy = top + CardWidget.HEADER_HEIGHT
        val coloursInner = coloursWidth - PAD * 2
        // Card draws its own title; content starts below the header strip.
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
        var gy = top + CardWidget.HEADER_HEIGHT
        val generalInner = generalWidth - PAD * 2
        
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

        var oy = bottomTop + CardWidget.HEADER_HEIGHT
        val overlayInner = halfWidth - PAD * 2
        
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

        var dy = bottomTop + CardWidget.HEADER_HEIGHT
        val dungeonInner = rightWidth - PAD * 2
        
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
        var py = presetsTop + CardWidget.HEADER_HEIGHT
        

        // Measured, not guessed: "Block-type preset" and "Donor palette" are both wider than the 60
        // this used to reserve, so the label ran underneath the ‹ button next to it.
        val labelWidth = listOf(blocksLabel, typesLabel, palettesLabel)
            .maxOf { Theme.textWidth(it.message.string).toInt() } + GAP
        // Fixed, not stretched to fill: the ‹ › pair belongs against the name it steps through, and
        // a name field spanning the card left the › stranded halfway across it.
        val nameWidth = 120
        for ((label, prev, nameLine, next, manage) in listOf(
            PresetRow(blocksLabel, blocksPrev, blocksNameLine, blocksNext, blocksManage),
            PresetRow(typesLabel, typesPrev, typesNameLine, typesNext, typesManage),
            PresetRow(palettesLabel, palettesPrev, palettesNameLine, palettesNext, palettesManage),
        )) {
            var px = x + PAD
            label.setRectangle(labelWidth, TextLineWidget.HEIGHT, px, py + 5)
            px += labelWidth + GAP
            prev.setRectangle(20, ROW, px, py)
            px += 20 + GAP
            nameLine.setRectangle(nameWidth, TextLineWidget.HEIGHT, px, py + 5)
            px += nameWidth + GAP
            next.setRectangle(20, ROW, px, py)
            // Right-aligned against the card edge, so the three Manage buttons form a column.
            manage.setRectangle(150, ROW, x + contentWidth - PAD - 150, py)
            py += ROW + ROW_GAP
        }
        val presetsBottom = py - ROW_GAP + PAD
        presetsPanel.setRectangle(contentWidth, presetsBottom - presetsTop, x, presetsTop)
    }

    private data class PresetRow(
        val label: TextLineWidget,
        val prev: ActButtonWidget,
        val nameLine: TextLineWidget,
        val next: ActButtonWidget,
        val manage: ActButtonWidget,
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
        overlayRadiusStepper.value = ApSettings.paintedOverlayRadius

        roomScopeToggle.checked = ApSettings.dungeonRoomScope


        blocksNameLine.message = Component.literal(PresetStores.blocks.activeName)
        typesNameLine.message = Component.literal(PresetStores.types.activeName)
        palettesNameLine.message = Component.literal(PresetStores.palettes.activeName)
    }
}
