package com.maxisch.client.gui

import com.maxisch.paint.PalettePreset
import com.maxisch.paint.PresetStores
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block

/**
 * Edits the active palette: which donor blocks an area apply draws from, and how often each one
 * comes up. Weights are relative, so the row shows the share they work out to rather than the raw
 * number alone.
 */
class PaletteScreen(private val parent: Screen?) :
    Screen(Component.translatable("austrianpainter.palette.title")) {

    private companion object {
        const val MARGIN = 8
        const val HEADER = 40
        const val FOOTER = 56
        const val ROW_HEIGHT = 16

        const val COARSE_STEP = 10
    }

    private var rows: List<Block> = emptyList()
    private var selected: Block? = null
    private var scroll = 0

    private lateinit var removeButton: Button

    private val palette: PalettePreset get() = PresetStores.palettes.active

    private val listX get() = MARGIN
    private val listY get() = HEADER
    private val listWidth get() = width - MARGIN * 2
    private val listRows get() = ((height - HEADER - FOOTER) / ROW_HEIGHT).coerceAtLeast(1)

    override fun init() {
        var x = MARGIN
        addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.palette.add")) {
                minecraft.setScreenAndShow(BlockPickerScreen(this) { block -> add(block) })
            }.bounds(x, height - FOOTER + 6, 110, 20).build(),
        )
        x += 114

        removeButton = addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.palette.remove")) { remove() }
                .bounds(x, height - FOOTER + 6, 100, 20).build(),
        )
        x += 104

        addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.palette.manage")) {
                minecraft.setScreenAndShow(PresetScreen.palettes(this))
            }.bounds(x, height - FOOTER + 6, 140, 20).build(),
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

    // ------------------------------------------------------------------ mutation

    private fun add(block: Block) {
        palette.weights.putIfAbsent(block, PalettePreset.MIN_WEIGHT)
        selected = block
        save()
    }

    private fun remove() {
        val block = selected ?: return
        if (palette.weights.remove(block) == null) return
        selected = null
        save()
    }

    private fun adjust(block: Block, delta: Int) {
        val current = palette.weights[block] ?: return
        val next = (current + delta).coerceIn(PalettePreset.MIN_WEIGHT, PalettePreset.MAX_WEIGHT)
        if (next == current) return
        palette.weights[block] = next
        save()
    }

    /** Palettes hold at most a few dozen entries, so writing on every edit costs nothing. */
    private fun save() {
        PresetStores.palettes.saveActive()
        refresh()
    }

    private fun refresh() {
        rows = palette.weights.keys.toList()
        if (selected != null && selected !in rows) selected = null
        scroll = scroll.coerceIn(0, (rows.size - listRows).coerceAtLeast(0))
        removeButton.active = selected != null
    }

    // ------------------------------------------------------------------ input

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        if (super.mouseClicked(event, doubled)) return true

        val block = rowAt(event.x, event.y) ?: return false
        selected = block
        removeButton.active = true
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val direction = if (scrollY > 0) 1 else if (scrollY < 0) -1 else 0
        if (direction == 0) return false

        // Over a row the wheel edits that row's weight; anywhere else it scrolls the list.
        val block = rowAt(mouseX, mouseY)
        if (block != null) {
            adjust(block, direction * if (shiftHeld()) COARSE_STEP else 1)
            return true
        }

        scroll = (scroll - direction).coerceIn(0, (rows.size - listRows).coerceAtLeast(0))
        return true
    }

    /** A scroll carries no modifiers in 26.2, so ask the window directly. */
    private fun shiftHeld(): Boolean {
        val window = minecraft.window
        return InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT) ||
            InputConstants.isKeyDown(window, InputConstants.KEY_RSHIFT)
    }

    private fun rowAt(mouseX: Double, mouseY: Double): Block? {
        if (mouseX < listX || mouseX > listX + listWidth || mouseY < listY) return null
        val index = ((mouseY - listY) / ROW_HEIGHT).toInt()
        if (index !in 0 until listRows) return null
        return rows.getOrNull(scroll + index)
    }

    // ------------------------------------------------------------------ render

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        graphics.centeredText(font, title, width / 2, 8, 0xFFFFFFFF.toInt())
        graphics.text(
            font,
            Component.translatable(
                "austrianpainter.palette.header",
                PresetStores.palettes.activeName,
                palette.size,
            ),
            MARGIN, 24, 0xFFA0A0A0.toInt(),
        )

        drawRows(graphics, mouseX, mouseY)
    }

    private fun drawRows(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val listHeight = listRows * ROW_HEIGHT
        graphics.fill(listX - 2, listY - 2, listX + listWidth + 2, listY + listHeight + 2, 0x60000000)

        if (rows.isEmpty()) {
            graphics.text(
                font,
                Component.translatable("austrianpainter.palette.empty"),
                listX + 2, listY + 4, 0xFF808080.toInt(),
            )
            return
        }

        val total = palette.totalWeight().coerceAtLeast(1)

        graphics.enableScissor(listX, listY, listX + listWidth, listY + listHeight)
        for (index in 0 until listRows) {
            val block = rows.getOrNull(scroll + index) ?: break
            val weight = palette.weights[block] ?: continue
            val y = listY + index * ROW_HEIGHT

            if (block == selected) {
                graphics.fill(listX, y, listX + listWidth, y + ROW_HEIGHT, 0x8055FF55.toInt())
            } else if (mouseX in listX..(listX + listWidth) && mouseY in y until y + ROW_HEIGHT) {
                graphics.fill(listX, y, listX + listWidth, y + ROW_HEIGHT, 0x60FFFFFF)
            }

            // A bar behind the row makes the mix readable without doing the arithmetic.
            val barWidth = (listWidth - 4) * weight / total
            graphics.fill(listX + 2, y + ROW_HEIGHT - 3, listX + 2 + barWidth, y + ROW_HEIGHT - 1, 0x80FFAA00.toInt())

            val stack = ItemStack(block)
            if (!stack.isEmpty) graphics.item(stack, listX + 2, y)

            graphics.text(
                font,
                Component.translatable(
                    "austrianpainter.palette.row",
                    block.name,
                    weight,
                    weight * 100 / total,
                ),
                listX + 22, y + 4, 0xFFFFFFFF.toInt(),
            )
        }
        graphics.disableScissor()

        if (rowAt(mouseX.toDouble(), mouseY.toDouble()) != null) {
            graphics.setTooltipForNextFrame(
                Component.translatable("austrianpainter.palette.weight_hint"), mouseX, mouseY,
            )
        }
    }

    override fun isPauseScreen(): Boolean = false
}
