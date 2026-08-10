package com.maxisch.client.gui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import com.maxisch.paint.ApSettings
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
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

        /** The recents row plus the label above it. */
        const val RECENT_HEIGHT = 30

        val ALL_BLOCKS: List<Block> by lazy {
            BuiltInRegistries.BLOCK.filter { it != Blocks.AIR }
        }
    }

    private var filtered: List<Block> = ALL_BLOCKS
    private var scrollRow = 0

    /** Resolved once on open; ids that no longer exist are simply dropped. */
    private var recent: List<Block> = emptyList()

    private lateinit var search: EditBox

    /**
     * Hidden while a query is typed: the strip would otherwise shift the grid up and down under the
     * cursor as the search narrows.
     */
    private val showRecent
        get() = recent.isNotEmpty() && ::search.isInitialized && search.value.isBlank()

    private val recentX get() = MARGIN
    private val recentY get() = HEADER + 10

    private val gridX get() = MARGIN
    private val gridY get() = HEADER + if (showRecent) RECENT_HEIGHT else 0
    private val gridWidth get() = width - MARGIN * 2
    private val gridHeight get() = height - gridY - FOOTER
    private val columns get() = (gridWidth / CELL).coerceAtLeast(1)
    private val rows get() = (gridHeight / CELL).coerceAtLeast(1)

    override fun init() {
        recent = ApSettings.recentDonorIds().mapNotNull { id ->
            Identifier.tryParse(id)?.takeIf { BuiltInRegistries.BLOCK.containsKey(it) }
                ?.let { BuiltInRegistries.BLOCK.getValue(it) }
        }

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
            pick(it)
            return true
        }
        return false
    }

    private fun pick(block: Block) {
        ApSettings.rememberDonor(BuiltInRegistries.BLOCK.getKey(block).toString())
        onPick(block)
        onClose()
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val delta = if (scrollY > 0) -1 else if (scrollY < 0) 1 else 0
        if (delta == 0) return false

        val maxRow = ((filtered.size + columns - 1) / columns - rows).coerceAtLeast(0)
        scrollRow = (scrollRow + delta).coerceIn(0, maxRow)
        return true
    }

    private fun blockAt(mouseX: Double, mouseY: Double): Block? {
        recentAt(mouseX, mouseY)?.let { return it }

        if (mouseX < gridX || mouseY < gridY) return null
        val col = ((mouseX - gridX) / CELL).toInt()
        val row = ((mouseY - gridY) / CELL).toInt()
        if (col !in 0 until columns || row !in 0 until rows) return null
        return filtered.getOrNull((scrollRow + row) * columns + col)
    }

    private fun recentAt(mouseX: Double, mouseY: Double): Block? {
        if (!showRecent) return null
        if (mouseX < recentX || mouseY < recentY || mouseY >= recentY + CELL) return null
        return recent.getOrNull(((mouseX - recentX) / CELL).toInt())
    }

    // ------------------------------------------------------------------ render

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        graphics.centeredText(font, title, width / 2, 8, 0xFFFFFFFF.toInt())
        drawRecent(graphics, mouseX, mouseY)
        graphics.fill(gridX - 2, gridY - 2, gridX + columns * CELL + 2, gridY + rows * CELL + 2, 0x60000000)
        graphics.enableScissor(gridX, gridY, gridX + columns * CELL, gridY + rows * CELL)

        for (row in 0 until rows) {
            for (col in 0 until columns) {
                val block = filtered.getOrNull((scrollRow + row) * columns + col) ?: continue
                drawCell(graphics, block, gridX + col * CELL, gridY + row * CELL, mouseX, mouseY)
            }
        }
        graphics.disableScissor()

        blockAt(mouseX.toDouble(), mouseY.toDouble())?.let {
            graphics.setTooltipForNextFrame(it.name, mouseX, mouseY)
        }
    }

    /** The donors picked most recently, so the common case never needs the search box at all. */
    private fun drawRecent(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (!showRecent) return

        graphics.text(font, Component.translatable("austrianpainter.picker.recent"), recentX, HEADER, 0xFFA0A0A0.toInt())
        graphics.fill(
            recentX - 2,
            recentY - 2,
            recentX + recent.size * CELL + 2,
            recentY + CELL + 2,
            0x60000000,
        )
        recent.forEachIndexed { index, block ->
            drawCell(graphics, block, recentX + index * CELL, recentY, mouseX, mouseY)
        }
    }

    /** One cell of either strip: hover fill, then the item, or an outline for an item-less block. */
    private fun drawCell(
        graphics: GuiGraphicsExtractor,
        block: Block,
        x: Int,
        y: Int,
        mouseX: Int,
        mouseY: Int,
    ) {
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
