package com.maxisch.client.gui

import com.maxisch.paint.ApPaths
import com.maxisch.paint.PaintStorage
import com.maxisch.paint.PresetStore
import com.maxisch.paint.PresetStores
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * Manages one preset folder. Instantiated once per kind rather than duplicated, since the only
 * differences are the store it edits and how a switch is applied.
 */
class PresetScreen private constructor(
    private val parent: Screen?,
    private val store: PresetStore<*>,
    private val titleKey: String,
    private val activate: (String) -> Unit,
) : Screen(Component.translatable(titleKey)) {

    companion object {
        fun blocks(parent: Screen?) = PresetScreen(
            parent,
            PresetStores.blocks,
            "austrianpainter.presets.blocks",
            PaintStorage::activateBlockPreset,
        )

        fun types(parent: Screen?) = PresetScreen(
            parent,
            PresetStores.types,
            "austrianpainter.presets.types",
            PaintStorage::activateTypePreset,
        )

        private const val MARGIN = 8
        private const val HEADER = 30
        private const val FOOTER = 82
        private const val ROW_HEIGHT = 14
    }

    private var names: List<String> = emptyList()
    private var selected: String? = null
    private var scroll = 0

    private lateinit var nameBox: EditBox
    private lateinit var activateButton: Button
    private lateinit var duplicateButton: Button
    private lateinit var renameButton: Button
    private lateinit var deleteButton: Button

    private val listX get() = MARGIN
    private val listY get() = HEADER
    private val listWidth get() = width - MARGIN * 2
    private val listRows get() = ((height - HEADER - FOOTER) / ROW_HEIGHT).coerceAtLeast(1)

    override fun init() {
        nameBox = addRenderableWidget(
            EditBox(font, MARGIN, height - FOOTER + 6, 160, 20, Component.translatable("austrianpainter.presets.name")),
        )
        nameBox.setHint(Component.translatable("austrianpainter.presets.name"))
        nameBox.setResponder { refreshButtons() }

        var x = MARGIN + 164
        addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.presets.new")) {
                store.create(nameBox.value)?.let { select(it) }
            }.bounds(x, height - FOOTER + 6, 70, 20).build(),
        )
        x += 74

        duplicateButton = addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.presets.duplicate")) {
                selected?.let { from -> store.duplicate(from, nameBox.value)?.let { select(it) } }
            }.bounds(x, height - FOOTER + 6, 84, 20).build(),
        )
        x += 88

        renameButton = addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.presets.rename")) { rename() }
                .bounds(x, height - FOOTER + 6, 70, 20).build(),
        )

        activateButton = addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.presets.activate")) {
                selected?.let {
                    activate(it)
                    refresh()
                }
            }.bounds(MARGIN, height - 26, 100, 20).build(),
        )

        deleteButton = addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.presets.delete")) { delete() }
                .bounds(MARGIN + 104, height - 26, 100, 20).build(),
        )

        addRenderableWidget(
            Button.builder(Component.translatable("gui.done")) { onClose() }
                .bounds(width - MARGIN - 100, height - 26, 100, 20).build(),
        )

        refresh()
    }

    override fun onClose() {
        val target = parent
        if (target == null) super.onClose() else minecraft.setScreenAndShow(target)
    }

    private fun select(name: String) {
        selected = name
        nameBox.value = ""
        refresh()
    }

    private fun rename() {
        val from = selected ?: return
        val to = store.rename(from, nameBox.value) ?: return
        com.maxisch.paint.ApSettings.renamePreset(store === PresetStores.blocks, from, to)
        select(to)
    }

    private fun delete() {
        val name = selected ?: return
        if (!store.delete(name)) return
        selected = null
        // The active preset may have just been deleted; reload so state matches the folder.
        if (store.activeName == name) activate(com.maxisch.paint.ApSettings.DEFAULT_PRESET)
        refresh()
    }

    private fun refresh() {
        names = store.listWithActive()
        scroll = scroll.coerceIn(0, (names.size - listRows).coerceAtLeast(0))
        if (selected != null && selected !in names) selected = null
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

    // ------------------------------------------------------------------ input

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        if (super.mouseClicked(event, doubled)) return true

        nameAt(event.x, event.y)?.let {
            selected = it
            refreshButtons()
            return true
        }
        return false
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val delta = if (scrollY > 0) -1 else if (scrollY < 0) 1 else 0
        if (delta == 0) return false
        scroll = (scroll + delta).coerceIn(0, (names.size - listRows).coerceAtLeast(0))
        return true
    }

    private fun nameAt(mouseX: Double, mouseY: Double): String? {
        if (mouseX < listX || mouseX > listX + listWidth || mouseY < listY) return null
        val index = ((mouseY - listY) / ROW_HEIGHT).toInt()
        if (index !in 0 until listRows) return null
        return names.getOrNull(scroll + index)
    }

    // ------------------------------------------------------------------ render

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        graphics.centeredText(font, title, width / 2, 10, 0xFFFFFFFF.toInt())

        val listHeight = listRows * ROW_HEIGHT
        graphics.fill(listX - 2, listY - 2, listX + listWidth + 2, listY + listHeight + 2, 0x60000000)
        graphics.enableScissor(listX, listY, listX + listWidth, listY + listHeight)

        for (index in 0 until listRows) {
            val name = names.getOrNull(scroll + index) ?: break
            val y = listY + index * ROW_HEIGHT

            if (name == selected) {
                graphics.fill(listX, y, listX + listWidth, y + ROW_HEIGHT, 0x8055FF55.toInt())
            } else if (mouseX in listX..(listX + listWidth) && mouseY in y until y + ROW_HEIGHT) {
                graphics.fill(listX, y, listX + listWidth, y + ROW_HEIGHT, 0x60FFFFFF)
            }

            val label = if (name == store.activeName) {
                Component.translatable("austrianpainter.presets.row_active", name, store.entryCount(name))
            } else {
                Component.translatable("austrianpainter.presets.row", name, store.entryCount(name))
            }
            graphics.text(font, label, listX + 4, y + 3, 0xFFFFFFFF.toInt())
        }
        graphics.disableScissor()
    }

    override fun isPauseScreen(): Boolean = false
}
