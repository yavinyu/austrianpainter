package com.maxisch.client.gui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/**
 * Searchable grid of every block. Hands the chosen block back to whoever opened it and returns to
 * that screen, so both the rule manager and the brush can share one picker.
 */
class BlockPickerScreen(
    parent: Screen?,
    private val onPick: (Block) -> Unit,
) : ApScreen(Component.translatable("austrianpainter.picker.title"), parent) {

    private companion object {
        const val CELL = 20
        const val MARGIN = 8
        const val HEADER = 46
        const val FOOTER = 32

        val ALL_BLOCKS: List<Block> by lazy {
            BuiltInRegistries.BLOCK.filter { it != Blocks.AIR }
        }
    }

    private var filtered: List<Block> = ALL_BLOCKS
    private var scrollRow = 0

    private lateinit var search: EditBox

    private val gridX get() = MARGIN
    private val gridY get() = HEADER
    private val gridWidth get() = width - MARGIN * 2
    private val gridHeight get() = height - HEADER - FOOTER
    private val columns get() = (gridWidth / CELL).coerceAtLeast(1)
    private val rows get() = (gridHeight / CELL).coerceAtLeast(1)

    override fun init() {
        search = addRenderableWidget(
            EditBox(font, gridX, 22, gridWidth, 18, Component.translatable("austrianpainter.search")),
        )
        search.setHint(Component.translatable("austrianpainter.search"))
        search.setResponder { applyFilter(it) }

        addRenderableWidget(
            Button.builder(Component.translatable("gui.cancel")) { onClose() }
                .bounds(width / 2 - 50, height - 26, 100, 20).build(),
        )

        applyFilter(search.value)
        setInitialFocus(search)
    }

    private fun applyFilter(query: String) {
        val normalized = BlockSearch.normalize(query)
        filtered = if (normalized.isEmpty()) {
            ALL_BLOCKS
        } else {
            ALL_BLOCKS.filter { BlockSearch.matches(it, normalized) }
        }
        scrollRow = 0
    }

    // ------------------------------------------------------------------ input

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        if (super.mouseClicked(event, doubled)) return true

        blockAt(event.x, event.y)?.let {
            onPick(it)
            onClose()
            return true
        }
        return false
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val delta = if (scrollY > 0) -1 else if (scrollY < 0) 1 else 0
        if (delta == 0) return false

        val maxRow = ((filtered.size + columns - 1) / columns - rows).coerceAtLeast(0)
        scrollRow = (scrollRow + delta).coerceIn(0, maxRow)
        return true
    }

    private fun blockAt(mouseX: Double, mouseY: Double): Block? {
        if (mouseX < gridX || mouseY < gridY) return null
        val col = ((mouseX - gridX) / CELL).toInt()
        val row = ((mouseY - gridY) / CELL).toInt()
        if (col !in 0 until columns || row !in 0 until rows) return null
        return filtered.getOrNull((scrollRow + row) * columns + col)
    }

    // ------------------------------------------------------------------ render

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        graphics.centeredText(font, title, width / 2, 8, 0xFFFFFFFF.toInt())
        graphics.fill(gridX - 2, gridY - 2, gridX + columns * CELL + 2, gridY + rows * CELL + 2, 0x60000000)
        graphics.enableScissor(gridX, gridY, gridX + columns * CELL, gridY + rows * CELL)

        for (row in 0 until rows) {
            for (col in 0 until columns) {
                val block = filtered.getOrNull((scrollRow + row) * columns + col) ?: continue
                val x = gridX + col * CELL
                val y = gridY + row * CELL
                if (mouseX in x until x + CELL && mouseY in y until y + CELL) {
                    graphics.fill(x, y, x + CELL, y + CELL, 0x60FFFFFF)
                }
                val stack = ItemStack(block)
                if (stack.isEmpty) {
                    graphics.outline(x + 3, y + 3, CELL - 6, CELL - 6, 0xFF808080.toInt())
                } else {
                    graphics.item(stack, x + 2, y + 2)
                }
            }
        }
        graphics.disableScissor()

        blockAt(mouseX.toDouble(), mouseY.toDouble())?.let {
            graphics.setTooltipForNextFrame(it.name, mouseX, mouseY)
        }
    }
}
