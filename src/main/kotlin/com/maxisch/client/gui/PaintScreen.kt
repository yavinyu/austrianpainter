package com.maxisch.client.gui

import com.maxisch.client.PaintBrush
import com.maxisch.client.PaintSelection
import com.maxisch.paint.PaintRule
import com.maxisch.paint.PaintStorage
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/**
 * The paint menu: pick a texture donor on the left, review the rules that already exist on the
 * right, and apply one for the current mode.
 */
class PaintScreen : Screen(Component.translatable("austrianpainter.screen.title")) {

    private companion object {
        const val CELL = 20
        const val PANEL_MARGIN = 8
        const val HEADER = 46
        const val FOOTER = 56

        val ALL_BLOCKS: List<Block> by lazy {
            BuiltInRegistries.BLOCK.filter { it != Blocks.AIR }
        }
    }

    private var filtered: List<Block> = ALL_BLOCKS
    private var scrollRow = 0
    private var ruleScroll = 0

    /** Captured when the screen opens, because the crosshair is frozen while a screen is up. */
    private var lookedAtPos: BlockPos? = null
    private var lookedAtBlock: Block? = null

    private lateinit var search: EditBox
    private lateinit var soundButton: Button
    private lateinit var brushButton: Button
    private lateinit var applyButton: Button

    private val gridX get() = PANEL_MARGIN
    private val gridY get() = HEADER
    private val gridWidth get() = width / 2 - PANEL_MARGIN * 2
    private val gridHeight get() = height - HEADER - FOOTER
    private val gridColumns get() = (gridWidth / CELL).coerceAtLeast(1)
    private val gridRows get() = (gridHeight / CELL).coerceAtLeast(1)

    private val listX get() = width / 2 + PANEL_MARGIN
    private val listWidth get() = width / 2 - PANEL_MARGIN * 2
    private val rowHeight = 12
    private val listRows get() = (gridHeight / rowHeight).coerceAtLeast(1)

    override fun init() {
        lookedAtPos = PaintSelection.lookedAtPos()
        lookedAtBlock = PaintSelection.lookedAtBlock()

        search = addRenderableWidget(
            EditBox(font, gridX, 22, gridWidth, 18, Component.translatable("austrianpainter.search")),
        )
        search.setHint(Component.translatable("austrianpainter.search"))
        search.setResponder { applyFilter(it) }

        var x = PANEL_MARGIN
        for (mode in PaintSelection.Mode.entries) {
            addRenderableWidget(
                Button.builder(Component.translatable("austrianpainter.mode.${mode.name.lowercase()}")) {
                    PaintSelection.mode = mode
                    refreshButtons()
                }.bounds(x, height - FOOTER + 6, 66, 20).build(),
            )
            x += 68
        }

        soundButton = addRenderableWidget(
            Button.builder(soundLabel()) {
                PaintSelection.paintSound = !PaintSelection.paintSound
                PaintBrush.paintSound = PaintSelection.paintSound
                soundButton.message = soundLabel()
            }.bounds(x, height - FOOTER + 6, 96, 20).build(),
        )

        brushButton = addRenderableWidget(
            Button.builder(brushLabel()) {
                PaintBrush.enabled = !PaintBrush.enabled
                brushButton.message = brushLabel()
            }.bounds(x + 100, height - FOOTER + 6, 110, 20).build(),
        )

        applyButton = addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.apply")) { apply() }
                .bounds(PANEL_MARGIN, height - 26, 100, 20).build(),
        )

        addRenderableWidget(
            Button.builder(Component.translatable("austrianpainter.clear_all")) {
                PaintStorage.clearCurrentDimension()
            }.bounds(PANEL_MARGIN + 104, height - 26, 100, 20).build(),
        )

        addRenderableWidget(
            Button.builder(Component.translatable("gui.done")) { onClose() }
                .bounds(width - PANEL_MARGIN - 100, height - 26, 100, 20).build(),
        )

        applyFilter(search.value)
        refreshButtons()
    }

    private fun soundLabel(): Component = Component.translatable(
        if (PaintSelection.paintSound) "austrianpainter.sound.on" else "austrianpainter.sound.off",
    )

    private fun brushLabel(): Component = Component.translatable(
        if (PaintBrush.enabled) "austrianpainter.brush.button_on" else "austrianpainter.brush.button_off",
        PaintBrush.radius,
    )

    private fun refreshButtons() {
        applyButton.active = pendingRule() != null
    }

    private fun applyFilter(query: String) {
        val trimmed = query.trim().lowercase()
        filtered = if (trimmed.isEmpty()) {
            ALL_BLOCKS
        } else {
            ALL_BLOCKS.filter { block ->
                BuiltInRegistries.BLOCK.getKey(block).toString().contains(trimmed) ||
                    block.name.string.lowercase().contains(trimmed)
            }
        }
        scrollRow = 0
    }

    // ------------------------------------------------------------------ rules

    private fun pendingRule(): PaintRule? {
        val target = PaintSelection.target ?: return null
        val sound = PaintSelection.paintSound
        return when (PaintSelection.mode) {
            PaintSelection.Mode.TYPE -> lookedAtBlock?.let { PaintRule.OfType(it, target, sound) }
            PaintSelection.Mode.POSITION ->
                (PaintSelection.corner1 ?: lookedAtPos)?.let { PaintRule.OfPos(it, target, sound) }

            PaintSelection.Mode.REGION -> {
                val a = PaintSelection.corner1 ?: return null
                val b = PaintSelection.corner2 ?: return null
                val rule = PaintRule.region(a, b, target, sound)
                if (rule.volume > PaintRule.MAX_REGION_VOLUME) null else rule
            }
        }
    }

    private fun apply() {
        val rule = pendingRule() ?: return
        PaintStorage.add(rule)
        refreshButtons()
    }

    // ------------------------------------------------------------------ input

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        if (super.mouseClicked(event, doubled)) return true

        blockAt(event.x, event.y)?.let {
            PaintSelection.target = it
            // Picking here also arms the brush, so the fast path needs no second trip.
            PaintBrush.donor = it
            refreshButtons()
            return true
        }
        ruleAt(event.x, event.y)?.let {
            PaintStorage.remove(it)
            return true
        }
        return false
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val delta = if (scrollY > 0) -1 else if (scrollY < 0) 1 else 0
        if (delta == 0) return false

        if (mouseX < width / 2.0) {
            val maxRow = ((filtered.size + gridColumns - 1) / gridColumns - gridRows).coerceAtLeast(0)
            scrollRow = (scrollRow + delta).coerceIn(0, maxRow)
        } else {
            val maxRow = (PaintStorage.currentRules().size - listRows).coerceAtLeast(0)
            ruleScroll = (ruleScroll + delta).coerceIn(0, maxRow)
        }
        return true
    }

    private fun blockAt(mouseX: Double, mouseY: Double): Block? {
        val col = ((mouseX - gridX) / CELL).toInt()
        val row = ((mouseY - gridY) / CELL).toInt()
        if (mouseX < gridX || mouseY < gridY) return null
        if (col !in 0 until gridColumns || row !in 0 until gridRows) return null
        val index = (scrollRow + row) * gridColumns + col
        return filtered.getOrNull(index)
    }

    private fun ruleAt(mouseX: Double, mouseY: Double): PaintRule? {
        if (mouseX < listX || mouseX > listX + listWidth) return null
        val row = ((mouseY - gridY) / rowHeight).toInt()
        if (row !in 0 until listRows) return null
        return PaintStorage.currentRules().getOrNull(ruleScroll + row)
    }

    // ------------------------------------------------------------------ render

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        graphics.centeredText(font, title, width / 2, 8, 0xFFFFFFFF.toInt())
        drawContext(graphics)
        drawGrid(graphics, mouseX, mouseY)
        drawRules(graphics, mouseX, mouseY)
    }

    private fun drawContext(graphics: GuiGraphicsExtractor) {
        val line = when (PaintSelection.mode) {
            PaintSelection.Mode.TYPE -> lookedAtBlock?.let {
                Component.translatable("austrianpainter.context.type", it.name)
            } ?: Component.translatable("austrianpainter.context.none")

            PaintSelection.Mode.POSITION -> (PaintSelection.corner1 ?: lookedAtPos)?.let {
                Component.translatable("austrianpainter.context.pos", it.x, it.y, it.z)
            } ?: Component.translatable("austrianpainter.context.none")

            PaintSelection.Mode.REGION -> {
                val a = PaintSelection.corner1
                val b = PaintSelection.corner2
                if (a == null || b == null) {
                    Component.translatable("austrianpainter.context.corners")
                } else {
                    val rule = PaintRule.region(a, b, Blocks.STONE, false)
                    Component.translatable("austrianpainter.context.region", rule.volume)
                }
            }
        }
        graphics.text(font, line, listX, 24, 0xFFA0A0A0.toInt())

        val target = PaintSelection.target
        val targetLine = if (target == null) {
            Component.translatable("austrianpainter.context.no_target")
        } else {
            Component.translatable("austrianpainter.context.target", target.name)
        }
        graphics.text(font, targetLine, listX, 34, 0xFFFFFF55.toInt())
    }

    private fun drawGrid(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        graphics.fill(gridX - 2, gridY - 2, gridX + gridColumns * CELL + 2, gridY + gridRows * CELL + 2, 0x60000000)
        graphics.enableScissor(gridX, gridY, gridX + gridColumns * CELL, gridY + gridRows * CELL)

        for (row in 0 until gridRows) {
            for (col in 0 until gridColumns) {
                val block = filtered.getOrNull((scrollRow + row) * gridColumns + col) ?: continue
                val x = gridX + col * CELL
                val y = gridY + row * CELL
                if (block == PaintSelection.target) {
                    graphics.fill(x, y, x + CELL, y + CELL, 0x8055FF55.toInt())
                } else if (mouseX in x until x + CELL && mouseY in y until y + CELL) {
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

    private fun drawRules(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val rules = PaintStorage.currentRules()
        graphics.fill(listX - 2, gridY - 2, listX + listWidth + 2, gridY + listRows * rowHeight + 2, 0x60000000)

        if (rules.isEmpty()) {
            graphics.text(font, Component.translatable("austrianpainter.rules.empty"), listX, gridY + 2, 0xFF808080.toInt())
            return
        }

        graphics.enableScissor(listX, gridY, listX + listWidth, gridY + listRows * rowHeight)
        for (row in 0 until listRows) {
            val rule = rules.getOrNull(ruleScroll + row) ?: break
            val y = gridY + row * rowHeight
            val hovered = mouseX in listX..(listX + listWidth) && mouseY in y until y + rowHeight
            if (hovered) graphics.fill(listX, y, listX + listWidth, y + rowHeight, 0x60FF5555)
            graphics.text(font, describe(rule), listX + 2, y + 2, 0xFFFFFFFF.toInt())
        }
        graphics.disableScissor()

        if (ruleAt(mouseX.toDouble(), mouseY.toDouble()) != null) {
            graphics.setTooltipForNextFrame(Component.translatable("austrianpainter.rules.delete"), mouseX, mouseY)
        }
    }

    private fun describe(rule: PaintRule): Component = when (rule) {
        is PaintRule.OfType -> Component.translatable(
            "austrianpainter.rule.type", rule.source.name, rule.target.name,
        )

        is PaintRule.OfPos -> Component.translatable(
            "austrianpainter.rule.pos", rule.pos.x, rule.pos.y, rule.pos.z, rule.target.name,
        )

        is PaintRule.OfRegion -> Component.translatable(
            "austrianpainter.rule.region", rule.volume, rule.target.name,
        )
    }

    override fun isPauseScreen(): Boolean = false
}
