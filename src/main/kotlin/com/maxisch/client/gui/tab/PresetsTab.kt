package com.maxisch.client.gui.tab

import com.maxisch.client.gui.BlockPickerScreen
import com.maxisch.client.gui.PainterScreen
import com.maxisch.client.gui.Theme
import com.maxisch.client.gui.nvgSurface
import com.maxisch.client.render.render2d.Gradient
import com.maxisch.client.render.render2d.NVGUtils
import com.maxisch.client.gui.widget.ActButtonWidget
import com.maxisch.client.gui.widget.CardWidget
import com.maxisch.client.gui.widget.RowContent
import com.maxisch.client.gui.widget.RowListWidget
import com.maxisch.client.gui.widget.TextLineWidget
import com.maxisch.paint.ApPaths
import com.maxisch.paint.ApSettings
import com.maxisch.paint.PaintStorage
import com.maxisch.paint.PalettePreset
import com.maxisch.paint.PresetKind
import com.maxisch.paint.PresetStore
import com.maxisch.paint.PresetStores
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import com.maxisch.client.gui.widget.ApEditBox
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block

/**
 * Manages every preset folder, including the active palette's donor weights - folded in here
 * rather than kept as its own tab, since a palette is just another preset kind and weight-editing
 * only ever applies to the one that is active anyway.
 *
 * One widget set rather than one per kind: only the store it edits and how a switch is applied
 * differ, and those swap without rebuilding anything. A segmented row picks the kind directly
 * instead of cycling through one button, a quick-swap cycler steps the active preset without
 * selecting a row first, and double-clicking a row activates it immediately.
 *
 * Every refused action says why. `create`/`rename`/`duplicate` return null for "blank or taken",
 * which used to leave the screen silently doing nothing.
 */
class PresetsTab(private val screen: PainterScreen) : ApTab("austrianpainter.tab.presets") {

    private companion object {
        const val MARGIN = 8
        const val ROW_HEIGHT = 14
        const val PALETTE_ROW_HEIGHT = 16
        const val BUTTON_HEIGHT = 16
        const val GAP = 4
        const val GREY = 0xFFA0A0A0.toInt()
        const val BAR = 0x80FFAA00.toInt()

        /** Weight bar: left of the track, right margin, height and corner. */
        const val BAR_X = 22
        const val BAR_RIGHT_PAD = 6
        const val BAR_HEIGHT = 3
        const val BAR_RADIUS = 1.5f
        const val COARSE_STEP = 10
    }

    private var kind = PresetKind.BLOCKS
    private var selected: String? = null
    private var selectedDonor: Block? = null

    private val font get() = Minecraft.getInstance().font

    private val store: PresetStore<*> get() = PresetStores.of(kind)
    private val palette: PalettePreset get() = PresetStores.palettes.active

    /** Weight-editing only ever applies to the active palette - a non-active one selected here
     *  keeps the ordinary read-only contents view, same constraint the old Palette tab had. */
    private val editingActivePalette: Boolean
        get() = kind == PresetKind.PALETTES && selected != null && selected == store.activeName

    private val activate: (String) -> Unit
        get() = when (kind) {
            PresetKind.BLOCKS -> PaintStorage::activateBlockPreset
            PresetKind.ROOMS -> PaintStorage::activateRoomPreset
            PresetKind.BOSSES -> PaintStorage::activateBossPreset
            PresetKind.TYPES -> PaintStorage::activateTypePreset
            PresetKind.PALETTES -> PaintStorage::activatePalette
            PresetKind.RULESETS -> PaintStorage::activateRuleset
        }

    // ------------------------------------------------------------------ widgets

    private val kindButtons: List<ActButtonWidget> = PresetKind.entries.map { k ->
        add(ActButtonWidget.builder(kindLabel(k)) { selectKind(k) }.width(80).build())
    }

    private val headerLine = add(TextLineWidget(0, 0, 0, GREY))

    private val prevButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.presets.prev")) { stepActive(-1) }
            .width(20)
            .tooltip(Tooltip.create(Component.translatable("austrianpainter.presets.quick_swap_hint")))
            .build(),
    )

    private val nextButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.presets.next")) { stepActive(1) }
            .width(20)
            .tooltip(Tooltip.create(Component.translatable("austrianpainter.presets.quick_swap_hint")))
            .build(),
    )

    private val nameBox = add(
        ApEditBox(font, 0, 0, 160, 20, Component.translatable("austrianpainter.presets.name")).apply {
            setHint(Component.translatable("austrianpainter.presets.name"))
        },
    )

    private val newButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.presets.new")) { create() }.width(70).build(),
    )

    private val duplicateButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.presets.duplicate")) { duplicate() }
            .width(84).build(),
    )

    private val renameButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.presets.rename")) { rename() }.width(70).build(),
    )

    private val activateButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.presets.activate")) { activateSelected() }
            .width(100).build(),
    )

    private val deleteButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.presets.delete")) { delete() }.width(100).build(),
    )

    private val copyButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.presets.copy")) { copy() }.width(100).build(),
    )

    private val pasteButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.presets.paste")) { paste() }.width(100).build(),
    )

    // Cards first: they are the ground the two lists draw on.
    private val namesCard = add(
        CardWidget(Component.translatable("austrianpainter.card.presets")) {
            Component.literal(list.rows.size.toString())
        },
    )
    private val contentsCard = add(
        CardWidget(Component.translatable("austrianpainter.card.contents")) {
            Component.literal(if (editingActivePalette) paletteList.rows.size.toString() else contents.rows.size.toString())
        },
    )

    private val list = add(
        RowListWidget<String>(0, 0, 0, 0, ROW_HEIGHT).apply {
            emptyMessage = { Component.translatable("austrianpainter.presets.empty") }
            isSelected = { it == selected }
            onRowClick = { name -> selected = name; selectedDonor = null; refresh() }
            onRowDoubleClick = { name -> activatePreset(name) }
            drawRow = { graphics, name, x, y, _ ->
                val label = if (name == store.activeName) {
                    Component.translatable("austrianpainter.presets.row_active", name, store.entryCount(name))
                } else {
                    Component.translatable("austrianpainter.presets.row", name, store.entryCount(name))
                }
                graphics.text(font, label, x + 4, y + 3, 0xFFFFFFFF.toInt())
            }
        },
    )

    /** Read-only description of the selected preset - swapped out for [paletteList] only while
     *  [editingActivePalette]. */
    private val contents = add(
        RowListWidget<Component>(0, 0, 0, 0, ROW_HEIGHT).apply {
            emptyMessage = {
                if (selected == null) {
                    Component.translatable("austrianpainter.presets.contents_none")
                } else {
                    Component.translatable("austrianpainter.presets.contents_empty")
                }
            }
            drawRow = { graphics, line, x, y, _ ->
                graphics.text(font, line, x + 4, y + 3, 0xFFFFFFFF.toInt())
            }
        },
    )

    private val paletteAddButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.palette.add")) {
            Minecraft.getInstance().setScreenAndShow(BlockPickerScreen(screen) { block -> addDonor(block) })
        }.width(110).build(),
    )

    private val paletteRemoveButton = add(
        ActButtonWidget.builder(Component.translatable("austrianpainter.palette.remove")) { removeDonor() }
            .width(100).build(),
    )

    private val paletteList = add(
        RowListWidget<Block>(0, 0, 0, 0, PALETTE_ROW_HEIGHT).apply {
            emptyMessage = { Component.translatable("austrianpainter.palette.empty") }
            hoverTooltip = { weightHint() }
            isSelected = { it == selectedDonor }
            onRowClick = { block -> selectedDonor = block; refreshButtons() }
            onRowScroll = { block, direction ->
                adjustWeight(block, direction * if (shiftHeld()) COARSE_STEP else 1)
                true
            }
            drawRow = { graphics, block, x, y, _ -> drawPaletteRow(graphics, block, x, y) }
        },
    )

    init {
        nameBox.setResponder { refreshButtons() }
        paletteAddButton.setTooltip(Tooltip.create(weightHint()))
    }

    // ------------------------------------------------------------------ layout

    override fun layout(area: ScreenRectangle) {
        val x = area.left() + MARGIN
        val contentWidth = area.width() - MARGIN * 2
        var y = area.top() + GAP

        val segmentCount = kindButtons.size
        val segmentWidth = (contentWidth - GAP * (segmentCount - 1)) / segmentCount
        var segX = x
        for ((index, button) in kindButtons.withIndex()) {
            val w = if (index == kindButtons.lastIndex) {
                contentWidth - (segmentWidth + GAP) * (segmentCount - 1)
            } else {
                segmentWidth
            }
            button.setRectangle(w, BUTTON_HEIGHT, segX, y)
            segX += w + GAP
        }
        y += BUTTON_HEIGHT + GAP

        prevButton.setRectangle(prevButton.width, BUTTON_HEIGHT, x, y)
        nextButton.setRectangle(nextButton.width, BUTTON_HEIGHT, x + contentWidth - nextButton.width, y)
        headerLine.setRectangle(
            (contentWidth - prevButton.width - nextButton.width - GAP * 2).coerceAtLeast(0),
            TextLineWidget.HEIGHT,
            x + prevButton.width + GAP,
            y + 5,
        )
        y += BUTTON_HEIGHT + GAP

        nameBox.setRectangle(160, BUTTON_HEIGHT, x, y)
        var buttonX = x + 164
        for (button in listOf(newButton, duplicateButton, renameButton)) {
            button.setRectangle(button.width, BUTTON_HEIGHT, buttonX, y)
            buttonX += button.width + GAP
        }
        y += BUTTON_HEIGHT + GAP

        var actionX = x
        for (button in listOf(activateButton, deleteButton, copyButton, pasteButton)) {
            button.setRectangle(button.width, BUTTON_HEIGHT, actionX, y)
            actionX += button.width + GAP
        }
        y += BUTTON_HEIGHT + GAP * 2

        // Names on the left, what is inside the selected one (or the palette editor) on the right.
        // The gap is doubled so the two scissored lists never look like one.
        val namesWidth = ((contentWidth - GAP * 2) * 2 / 5).coerceAtLeast(ROW_HEIGHT)
        val contentsX = x + namesWidth + GAP * 2
        val contentsWidth = (contentWidth - namesWidth - GAP * 2).coerceAtLeast(ROW_HEIGHT)

        // The palette-editor toolbar's row is reserved above both lists whichever kind is active,
        // so switching into palette-editing never has to relayout anything - it just becomes visible.
        paletteAddButton.setRectangle(paletteAddButton.width, BUTTON_HEIGHT, contentsX, y)
        paletteRemoveButton.setRectangle(
            paletteRemoveButton.width,
            BUTTON_HEIGHT,
            contentsX + paletteAddButton.width + GAP,
            y,
        )

        val listsY = y + BUTTON_HEIGHT + GAP
        val listHeight = (area.bottom() - listsY - GAP).coerceAtLeast(ROW_HEIGHT)

        // Names on the left, the selected preset's contents on the right, each in its own card.
        namesCard.setRectangle(namesWidth, listHeight, x, listsY)
        contentsCard.setRectangle(contentsWidth, listHeight, contentsX, listsY)

        val innerTop = listsY + CardWidget.HEADER_HEIGHT
        val innerHeight = (listHeight - CardWidget.HEADER_HEIGHT - CardWidget.PAD).coerceAtLeast(ROW_HEIGHT)
        list.setRectangle(namesWidth - CardWidget.PAD * 2, innerHeight, x + CardWidget.PAD, innerTop)
        contents.setRectangle(contentsWidth - CardWidget.PAD * 2, innerHeight, contentsX + CardWidget.PAD, innerTop)
        paletteList.setRectangle(contentsWidth - CardWidget.PAD * 2, innerHeight, contentsX + CardWidget.PAD, innerTop)
    }

    // ------------------------------------------------------------------ kind / active-preset

    fun selectKind(kind: PresetKind) {
        this.kind = kind
        selected = null
        selectedDonor = null
        refresh()
    }

    private fun stepActive(delta: Int) {
        val names = store.listWithActive()
        if (names.isEmpty()) return
        val index = names.indexOf(store.activeName)
        activatePreset(names[(index + delta).mod(names.size)])
    }

    private fun activatePreset(name: String) {
        activate(name)
        refresh()
        screen.status(Component.translatable("austrianpainter.presets.activated", name))
    }

    private fun activateSelected() {
        selected?.let(::activatePreset)
    }

    // ------------------------------------------------------------------ preset management

    private fun create() {
        val name = store.create(nameBox.value)
        if (name == null) {
            refuse(nameBox.value)
            return
        }
        selectCreated(name)
        screen.status(Component.translatable("austrianpainter.presets.created", name))
    }

    private fun duplicate() {
        val from = selected ?: return
        val name = store.duplicate(from, nameBox.value)
        if (name == null) {
            refuse(nameBox.value)
            return
        }
        selectCreated(name)
        screen.status(Component.translatable("austrianpainter.presets.created", name))
    }

    private fun rename() {
        val from = selected ?: return
        val to = store.rename(from, nameBox.value)
        if (to == null) {
            refuse(nameBox.value)
            return
        }
        ApSettings.renamePreset(kind, from, to)
        selectCreated(to)
        screen.status(Component.translatable("austrianpainter.presets.renamed", from, to))
    }

    private fun delete() {
        val name = selected ?: return
        if (!store.delete(name)) return
        selected = null
        // The active preset may have just been deleted; reload so state matches the folder.
        if (store.activeName == name) activate(ApSettings.DEFAULT_PRESET)
        refresh()
        screen.status(Component.translatable("austrianpainter.presets.deleted", name))
    }

    /**
     * Copies the *active* preset rather than the selected one: only the active one is guaranteed to
     * hold unsaved edits, and copying a stale file would be a quiet lie.
     */
    private fun copy() {
        Minecraft.getInstance().keyboardHandler.setClipboard(store.exportActive())
        screen.status(Component.translatable("austrianpainter.presets.copied", store.activeName))
    }

    /** Always a new preset from the name box - a paste can never silently replace existing work. */
    private fun paste() {
        val text = Minecraft.getInstance().keyboardHandler.clipboard
        if (text.isBlank()) {
            screen.status(Component.translatable("austrianpainter.presets.clipboard_empty"))
            return
        }

        val name = store.importAs(nameBox.value, text)
        if (name == null) {
            refuse(nameBox.value)
            return
        }
        selectCreated(name)
        screen.status(Component.translatable("austrianpainter.presets.pasted", name, store.entryCount(name)))
    }

    /** Says which of the two reasons a name was rejected for, rather than doing nothing. */
    private fun refuse(typed: String) {
        val clean = ApPaths.sanitize(typed)
        screen.status(
            if (clean.isEmpty()) {
                Component.translatable("austrianpainter.presets.name_blank")
            } else {
                Component.translatable("austrianpainter.presets.name_taken", clean)
            },
        )
    }

    private fun selectCreated(name: String) {
        selected = name
        selectedDonor = null
        nameBox.value = ""
        refresh()
    }

    // ------------------------------------------------------------------ palette editing

    private fun addDonor(block: Block) {
        val added = palette.weights.putIfAbsent(block, PalettePreset.MIN_WEIGHT) == null
        selectedDonor = block
        savePalette()
        screen.status(
            Component.translatable(
                if (added) "austrianpainter.palette.added" else "austrianpainter.palette.already",
                block.name,
            ),
        )
    }

    private fun removeDonor() {
        val block = selectedDonor ?: return
        if (palette.weights.remove(block) == null) return
        selectedDonor = null
        savePalette()
        screen.status(Component.translatable("austrianpainter.palette.removed", block.name))
    }

    private fun adjustWeight(block: Block, delta: Int) {
        val current = palette.weights[block] ?: return
        val next = (current + delta).coerceIn(PalettePreset.MIN_WEIGHT, PalettePreset.MAX_WEIGHT)
        if (next == current) return
        palette.weights[block] = next
        savePalette()
    }

    /** Palettes hold at most a few dozen entries, so writing on every edit costs nothing. */
    private fun savePalette() {
        PresetStores.palettes.saveActive()
        refresh()
    }

    private fun shiftHeld(): Boolean {
        val window = Minecraft.getInstance().window
        return InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT) ||
            InputConstants.isKeyDown(window, InputConstants.KEY_RSHIFT)
    }

    private fun weightHint(): Component =
        Component.translatable("austrianpainter.palette.weight_hint", COARSE_STEP)

    private fun drawPaletteRow(graphics: GuiGraphicsExtractor, block: Block, x: Int, y: Int) {
        val weight = palette.weights[block] ?: return
        val total = palette.totalWeight().coerceAtLeast(1)
        val percent = weight * 100 / total

        // The share of the mix, as a track the row's own bar fills. The old version was a 2px sliver
        // along the bottom edge with no track behind it, so a small weight was indistinguishable
        // from none at all and there was nothing to judge the fill against.
        val trackX = x + BAR_X
        val trackWidth = (paletteList.width - BAR_X - BAR_RIGHT_PAD).coerceAtLeast(1)
        val trackY = y + PALETTE_ROW_HEIGHT - BAR_HEIGHT - 2
        val fillWidth = (trackWidth * weight / total).coerceAtLeast(1)

        nvgSurface(
            graphics, trackX, trackY, trackWidth, BAR_HEIGHT,
            nvg = {
                NVGUtils.drawRect(
                    trackX.toFloat(),
                    trackY.toFloat(),
                    trackWidth.toFloat(),
                    BAR_HEIGHT.toFloat(),
                    BAR_RADIUS,
                    Theme.c(Theme.GROUND),
                )
                NVGUtils.drawGradientRect(
                    trackX.toFloat(),
                    trackY.toFloat(),
                    fillWidth.toFloat(),
                    BAR_HEIGHT.toFloat(),
                    BAR_RADIUS,
                    Theme.c(Theme.withAlpha(Theme.AMBER, 0xB0)),
                    Theme.c(Theme.AMBER),
                    Gradient.LeftToRight,
                )
            },
            vanilla = {
                graphics.fill(trackX, trackY, trackX + trackWidth, trackY + BAR_HEIGHT, Theme.GROUND)
                graphics.fill(trackX, trackY, trackX + fillWidth, trackY + BAR_HEIGHT, BAR)
            },
        )

        val labelX = RowContent.pair(graphics, x, y, PALETTE_ROW_HEIGHT, block, null)
        RowContent.label(
            graphics,
            labelX,
            // Raised by the bar's height so the name centres on the space above its own weight bar
            // rather than on the row including it.
            y - BAR_HEIGHT,
            PALETTE_ROW_HEIGHT,
            x + paletteList.width,
            text = block.name,
            trailing = Component.translatable("austrianpainter.palette.share", weight, percent),
        )
    }

    // ------------------------------------------------------------------ state

    override fun refresh() {
        list.rows = store.listWithActive()
        if (selected != null && selected !in list.rows) selected = null

        val editing = editingActivePalette
        if (selectedDonor != null && (!editing || selectedDonor !in palette.weights.keys)) selectedDonor = null

        contents.visible = !editing
        paletteList.visible = editing
        paletteAddButton.visible = editing
        paletteRemoveButton.visible = editing

        if (editing) {
            paletteList.rows = palette.weights.keys.toList()
        } else {
            contents.rows = selected?.let { store.contents(it) } ?: emptyList()
        }

        for ((index, k) in PresetKind.entries.withIndex()) {
            kindButtons[index].message = if (k == kind) kindLabel(k).withStyle(ChatFormatting.YELLOW) else kindLabel(k)
        }

        headerLine.message = Component.translatable("austrianpainter.presets.active", store.activeName)
        refreshButtons()
    }

    private fun refreshButtons() {
        val chosen = selected
        val proposed = ApPaths.sanitize(nameBox.value)
        val nameFree = proposed.isNotEmpty() && !store.exists(proposed)

        activateButton.active = chosen != null && chosen != store.activeName
        deleteButton.active = chosen != null
        duplicateButton.active = chosen != null && nameFree
        renameButton.active = chosen != null && nameFree
        pasteButton.active = nameFree

        paletteRemoveButton.active = selectedDonor != null
    }

    private fun kindLabel(k: PresetKind): MutableComponent = Component.translatable(
        when (k) {
            PresetKind.BLOCKS -> "austrianpainter.presets.blocks"
            PresetKind.ROOMS -> "austrianpainter.presets.rooms"
            PresetKind.BOSSES -> "austrianpainter.presets.bosses"
            PresetKind.TYPES -> "austrianpainter.presets.types"
            PresetKind.PALETTES -> "austrianpainter.presets.palettes"
            PresetKind.RULESETS -> "austrianpainter.presets.rulesets"
        },
    )
}
