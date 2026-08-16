package com.maxisch.client.gui.screen

import com.maxisch.client.gui.widget.ActButtonWidget
import com.maxisch.client.gui.widget.CardWidget
import com.maxisch.client.render.render2d.NVGUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import com.maxisch.client.gui.widget.ApEditBox
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import com.maxisch.client.render.model.RetexturePalette
import com.maxisch.paint.settings.ApSettings
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import com.maxisch.client.gui.ApScreen
import com.maxisch.client.gui.Theme
import com.maxisch.client.gui.nvgSurface

/**
 * Searchable grid of every block. Hands the chosen block back to whoever opened it and returns to
 * that screen, so both the rule manager and the brush can share one picker.
 */
class BlockPickerScreen(
    parent: Screen?,
    private val onPick: (Block) -> Unit,
) : ApScreen(Component.translatable("austrianpainter.picker.title"), parent) {

    private companion object {
        /**
         * 28px cell holding a 24px model.
         *
         * `graphics.item` has no size argument - it always draws 16x16 - so a bigger cell alone would
         * only add padding. The 1.5x pose scale in [drawCell] makes it exactly 24, and an integer
         * multiple of 16 is what keeps the texture sharp; 1.3x or 1.7x would soften it.
         */
        const val CELL = 28
        const val ITEM_SCALE = 1.5f
        const val ITEM_SIZE = 24
        const val MARGIN = 8

        /** Top of the overlay panel: below the armed bar, so the donor being replaced stays visible. */
        val PANEL_TOP get() = PainterFrame.ARMED_HEIGHT + MARGIN

        /** The search row inside the panel, under its title strip. */
        val HEADER get() = PANEL_TOP + CardWidget.HEADER_HEIGHT + 24

        const val FOOTER = 32

        /** How far a strip's background extends past the cells it holds. */
        const val PADDING = 2

        /** Space above the recent strip, for the "Recent" label - matches [recentY]. */
        const val RECENT_LABEL_GAP = 10

        /** Breathing room between the recent strip's background and the grid below it. */
        const val SECTION_GAP = 8

        const val RECENT_LABEL = 0xFFA0A0A0.toInt()

        /**
         * Excludes blocks with no item (fire, water, portals, ...) - nothing to show an icon for -
         * and blocks whose model bakes no quads and aren't an [net.minecraft.world.level.block.EntityBlock]
         * (structure_void, light, ...), which [RetexturePalette] already refuses as donors. Barrier
         * is the one exception: picking it is how a block gets painted invisible on purpose.
         */
        val ALL_BLOCKS: List<Block> by lazy {
            BuiltInRegistries.BLOCK.filter { block ->
                block != Blocks.AIR &&
                    !ItemStack(block).isEmpty &&
                    (block == Blocks.BARRIER || RetexturePalette.of(block).usable)
            }
        }
    }

    private var filtered: List<Block> = ALL_BLOCKS
    private var scrollRow = 0

    /** Resolved once on open; ids that no longer exist are simply dropped. */
    private var recent: List<Block> = emptyList()

    private lateinit var search: EditBox
    private lateinit var panel: CardWidget

    /**
     * Hidden while a query is typed: the strip would otherwise shift the grid up and down under the
     * cursor as the search narrows.
     */
    private val showRecent
        get() = recent.isNotEmpty() && ::search.isInitialized && search.value.isBlank()

    // Everything is inset inside the overlay panel, not against the screen edge.
    private val recentX get() = gridX
    private val recentY get() = HEADER + RECENT_LABEL_GAP

    /** The recent strip's label, background and the gap to the grid below it, combined. */
    private val recentHeight get() = RECENT_LABEL_GAP + CELL + PADDING + SECTION_GAP

    private val gridX get() = MARGIN + CardWidget.PAD
    private val gridY get() = HEADER + if (showRecent) recentHeight else 0
    private val gridWidth get() = width - (MARGIN + CardWidget.PAD) * 2
    private val gridHeight get() = height - gridY - FOOTER - MARGIN
    private val columns get() = (gridWidth / CELL).coerceAtLeast(1)

    /** How many rows fit in the space available - the *viewport's* capacity, not how many the
     *  current [filtered] list needs. Scroll math must keep using this even when [visibleRows] is
     *  smaller, so a still-long result set keeps scrolling correctly. */
    private val rows get() = (gridHeight / CELL).coerceAtLeast(1)

    /** How many rows [filtered] actually needs, ignoring viewport capacity. */
    private val neededRows get() = if (filtered.isEmpty()) 1 else (filtered.size + columns - 1) / columns

    /** What the grid actually draws/hit-tests: never more than fits, never more than is needed -
     *  so a short result list doesn't leave the rest of the viewport's background empty. */
    private val visibleRows get() = neededRows.coerceAtMost(rows)

    /** The overlay panel's height, hugging [visibleRows] instead of always claiming the full
     *  available screen space - reduces to the original fixed formula when [visibleRows] == [rows]. */
    private val panelHeight get() = (gridY - PANEL_TOP) + visibleRows * CELL + FOOTER

    override fun init() {
        PainterFrame.measure(height)
        recent = ApSettings.recentDonorIds().mapNotNull { id ->
            Identifier.tryParse(id)?.takeIf { BuiltInRegistries.BLOCK.containsKey(it) }
                ?.let { BuiltInRegistries.BLOCK.getValue(it) }
        }

        // Before the search box and the button: registration order is render order, and the frame is
        // the ground they sit on. No footer - this screen has its own.
        // init() runs again on resize, so sizing it here is enough; it carries no state of its own.
        addRenderableWidget(PainterFrame(contentLeft = 0, withFooter = false)).also {
            it.width = width
            it.height = height
        }

        // The picker is an overlay panel rather than a bare full-screen grid: it reads as something
        // opened on top of the tool, which is what it is, and the armed bar stays legible above it.
        // Sized in applyFilter() instead of here - it needs to hug whatever filtered.size is, not
        // just the space this screen happens to have.
        panel = addRenderableWidget(
            CardWidget(title) { Component.literal("${filtered.size} / ${ALL_BLOCKS.size}") },
        )

        val cancelWidth = 70
        val searchY = PANEL_TOP + CardWidget.HEADER_HEIGHT + 3
        search = addRenderableWidget(
            ApEditBox(
                font,
                gridX,
                searchY,
                gridWidth - cancelWidth - MARGIN,
                18,
                Component.translatable("austrianpainter.search"),
            ),
        )
        search.setHint(Component.translatable("austrianpainter.search"))
        search.setResponder { applyFilter(it) }

        // In the panel's own header, where the mockup puts it - not floating at the screen's bottom.
        addRenderableWidget(
            ActButtonWidget.builder(Component.translatable("gui.cancel")) { onClose() }
                .bounds(gridX + gridWidth - cancelWidth, searchY, cancelWidth, 18)
                .variant(ActButtonWidget.Variant.GHOST)
                .build(),
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
        panel.setRectangle(width - MARGIN * 2, panelHeight, MARGIN, PANEL_TOP)
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
        if (col !in 0 until columns || row !in 0 until visibleRows) return null
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

        // The "shown / total" readout is the panel card's trailing count now, so it is not drawn here.
        drawRecent(graphics, mouseX, mouseY)

        // The strip's own hover wins, so the grid never lights a cell the click would not hit.
        val hovered = if (recentAt(mouseX.toDouble(), mouseY.toDouble()) == null) {
            gridCellAt(mouseX, mouseY)
        } else {
            null
        }
        strip(graphics, gridX, gridY, columns * CELL, visibleRows * CELL, hovered)

        graphics.enableScissor(gridX, gridY, gridX + columns * CELL, gridY + visibleRows * CELL)
        for (row in 0 until visibleRows) {
            for (col in 0 until columns) {
                val block = filtered.getOrNull((scrollRow + row) * columns + col) ?: continue
                drawCell(graphics, block, gridX + col * CELL, gridY + row * CELL)
            }
        }
        graphics.disableScissor()

        drawHoverReadout(graphics, mouseX, mouseY)
    }

    /**
     * Name and namespaced id of the block under the cursor, on the footer line.
     *
     * Replaces the tooltip that used to follow the mouse: the id is what a rule actually stores, and
     * a tooltip covers the cells around the one being read, which is where the eye is going next.
     */
    private fun drawHoverReadout(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val block = blockAt(mouseX.toDouble(), mouseY.toDouble()) ?: return
        val y = height - FOOTER + 6
        graphics.text(font, block.name, gridX, y, Theme.TEXT)
        graphics.text(
            font,
            Component.literal(BuiltInRegistries.BLOCK.getKey(block).toString()),
            MARGIN,
            y + 11,
            Theme.TEXT_DIM,
        )
    }

    /** The donors picked most recently, so the common case never needs the search box at all. */
    private fun drawRecent(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (!showRecent) return

        graphics.text(font, Component.translatable("austrianpainter.picker.recent"), recentX, HEADER, RECENT_LABEL)

        val hoveredIndex = recentAt(mouseX.toDouble(), mouseY.toDouble())?.let { (mouseX - recentX) / CELL }
        strip(
            graphics,
            recentX,
            recentY,
            recent.size * CELL,
            CELL,
            hoveredIndex?.let { recentX + it * CELL to recentY },
        )
        recent.forEachIndexed { index, block ->
            drawCell(graphics, block, recentX + index * CELL, recentY)
        }
    }

    /**
     * A strip's background and the hover fill of the one cell under the cursor, as a single NanoVG
     * layer. Separate from [drawCell] because the items have to be drawn *after* the whole layer to
     * land above it, so the fills cannot stay interleaved with them.
     */
    private fun strip(
        graphics: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        hovered: Pair<Int, Int>?,
    ) {
        nvgSurface(
            graphics, x - PADDING, y - PADDING, w + PADDING * 2, h + PADDING * 2,
            nvg = {
                NVGUtils.drawRect(
                    (x - PADDING).toFloat(),
                    (y - PADDING).toFloat(),
                    (w + PADDING * 2).toFloat(),
                    (h + PADDING * 2).toFloat(),
                    Theme.RADIUS,
                    Theme.c(Theme.LIST_BACKGROUND),
                )
                hovered?.let { (cellX, cellY) ->
                    NVGUtils.drawRect(
                        cellX.toFloat(),
                        cellY.toFloat(),
                        CELL.toFloat(),
                        CELL.toFloat(),
                        Theme.c(Theme.HOVER),
                    )
                }
            },
            vanilla = {
                graphics.fill(x - PADDING, y - PADDING, x + w + PADDING, y + h + PADDING, Theme.LIST_BACKGROUND)
                hovered?.let { (cellX, cellY) ->
                    graphics.fill(cellX, cellY, cellX + CELL, cellY + CELL, Theme.HOVER)
                }
            },
        )
    }

    /** Top-left of the grid cell under the cursor, or null when it is off the grid or on a gap. */
    private fun gridCellAt(mouseX: Int, mouseY: Int): Pair<Int, Int>? {
        if (blockAt(mouseX.toDouble(), mouseY.toDouble()) == null) return null
        return gridX + (mouseX - gridX) / CELL * CELL to gridY + (mouseY - gridY) / CELL * CELL
    }

    /** One cell's contents: the item at 24px, or an outline for a block that has none. */
    private fun drawCell(graphics: GuiGraphicsExtractor, block: Block, x: Int, y: Int) {
        val stack = ItemStack(block)
        val inset = (CELL - ITEM_SIZE) / 2
        if (stack.isEmpty) {
            graphics.outline(x + inset, y + inset, ITEM_SIZE, ITEM_SIZE, Theme.TEXT_DIM)
            return
        }

        // Translate first, then scale, then draw at the origin. Scaling first would multiply the
        // position as well as the size, and dividing the position back out truncates to whole
        // pixels - which is exactly the softening the integer multiple is there to avoid.
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate((x + inset).toFloat(), (y + inset).toFloat())
        pose.scale(ITEM_SCALE, ITEM_SCALE)
        graphics.item(stack, 0, 0)
        pose.popMatrix()
    }
}
