package com.maxisch.client.gui.tab

import com.maxisch.client.gui.PainterScreen
import com.maxisch.client.gui.widget.RowListWidget
import com.maxisch.client.gui.widget.TextLineWidget
import com.maxisch.paint.ApPaths
import com.maxisch.paint.ApSettings
import com.maxisch.paint.PaintStorage
import com.maxisch.paint.PresetKind
import com.maxisch.paint.PresetStore
import com.maxisch.paint.PresetStores
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.network.chat.Component

/**
 * Manages every preset folder.
 *
 * One tab rather than one per kind, because the widget set is identical for every kind - only the
 * store it edits and how a switch is applied differ, and those swap without rebuilding anything.
 *
 * Every refused action says why. `create`/`rename`/`duplicate` return null for "blank or taken",
 * which used to leave the screen silently doing nothing.
 */
class PresetsTab(private val screen: PainterScreen) : ApTab("austrianpainter.tab.presets") {

    private companion object {
        const val MARGIN = 8
        const val ROW_HEIGHT = 14
        const val BUTTON_HEIGHT = 20
        const val GAP = 4
        const val GREY = 0xFFA0A0A0.toInt()
    }

    private var kind = PresetKind.BLOCKS
    private var selected: String? = null

    private val font get() = Minecraft.getInstance().font

    private val store: PresetStore<*> get() = PresetStores.of(kind)

    private val activate: (String) -> Unit
        get() = when (kind) {
            PresetKind.BLOCKS -> PaintStorage::activateBlockPreset
            PresetKind.ROOMS -> PaintStorage::activateRoomPreset
            PresetKind.BOSSES -> PaintStorage::activateBossPreset
            PresetKind.TYPES -> PaintStorage::activateTypePreset
            PresetKind.PALETTES -> PaintStorage::activatePalette
            PresetKind.RULESETS -> PaintStorage::activateRuleset
        }

    private val kindButton = add(
        Button.builder(kindLabel()) { cycleKind() }.width(170).build(),
    )

    private val headerLine = add(TextLineWidget(0, 0, 0, GREY))

    private val nameBox = add(
        EditBox(font, 0, 0, 160, 20, Component.translatable("austrianpainter.presets.name")).apply {
            setHint(Component.translatable("austrianpainter.presets.name"))
        },
    )

    private val newButton = add(
        Button.builder(Component.translatable("austrianpainter.presets.new")) { create() }.width(70).build(),
    )

    private val duplicateButton = add(
        Button.builder(Component.translatable("austrianpainter.presets.duplicate")) { duplicate() }
            .width(84).build(),
    )

    private val renameButton = add(
        Button.builder(Component.translatable("austrianpainter.presets.rename")) { rename() }.width(70).build(),
    )

    private val activateButton = add(
        Button.builder(Component.translatable("austrianpainter.presets.activate")) { activateSelected() }
            .width(100).build(),
    )

    private val deleteButton = add(
        Button.builder(Component.translatable("austrianpainter.presets.delete")) { delete() }.width(100).build(),
    )

    private val list = add(
        RowListWidget<String>(0, 0, 0, 0, ROW_HEIGHT).apply {
            emptyMessage = { Component.translatable("austrianpainter.presets.empty") }
            isSelected = { it == selected }
            onRowClick = { name ->
                selected = name
                refreshButtons()
            }
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

    init {
        nameBox.setResponder { refreshButtons() }
    }

    // ------------------------------------------------------------------ layout

    override fun doLayout(area: ScreenRectangle) {
        val x = area.left() + MARGIN
        val contentWidth = area.width() - MARGIN * 2
        var y = area.top() + GAP

        kindButton.setRectangle(kindButton.width, BUTTON_HEIGHT, x, y)
        headerLine.setRectangle(
            contentWidth - kindButton.width - GAP * 2,
            TextLineWidget.HEIGHT,
            x + kindButton.width + GAP * 2,
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

        activateButton.setRectangle(activateButton.width, BUTTON_HEIGHT, x, y)
        deleteButton.setRectangle(deleteButton.width, BUTTON_HEIGHT, x + activateButton.width + GAP, y)
        y += BUTTON_HEIGHT + GAP * 2

        val listHeight = (area.bottom() - y - GAP).coerceAtLeast(ROW_HEIGHT)
        list.setRectangle(contentWidth, listHeight, x, y)
    }

    // ------------------------------------------------------------------ actions

    private fun cycleKind() {
        val kinds = PresetKind.entries
        kind = kinds[(kind.ordinal + 1) % kinds.size]
        selected = null
        refresh()
    }

    private fun create() {
        val name = store.create(nameBox.value)
        if (name == null) {
            refuse(nameBox.value)
            return
        }
        select(name)
        screen.status(Component.translatable("austrianpainter.presets.created", name))
    }

    private fun duplicate() {
        val from = selected ?: return
        val name = store.duplicate(from, nameBox.value)
        if (name == null) {
            refuse(nameBox.value)
            return
        }
        select(name)
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
        select(to)
        screen.status(Component.translatable("austrianpainter.presets.renamed", from, to))
    }

    private fun activateSelected() {
        val name = selected ?: return
        activate(name)
        refresh()
        screen.status(Component.translatable("austrianpainter.presets.activated", name))
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

    private fun select(name: String) {
        selected = name
        nameBox.value = ""
        refresh()
    }

    // ------------------------------------------------------------------ state

    override fun refresh() {
        list.rows = store.listWithActive()
        if (selected != null && selected !in list.rows) selected = null
        kindButton.message = kindLabel()
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
    }

    private fun kindLabel(): Component = Component.translatable(
        when (kind) {
            PresetKind.BLOCKS -> "austrianpainter.presets.blocks"
            PresetKind.ROOMS -> "austrianpainter.presets.rooms"
            PresetKind.BOSSES -> "austrianpainter.presets.bosses"
            PresetKind.TYPES -> "austrianpainter.presets.types"
            PresetKind.PALETTES -> "austrianpainter.presets.palettes"
            PresetKind.RULESETS -> "austrianpainter.presets.rulesets"
        },
    )
}
