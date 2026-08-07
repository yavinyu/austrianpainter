package com.maxisch.client.gui.tab

import com.maxisch.client.gui.BlockPickerScreen
import com.maxisch.client.gui.PainterScreen
import com.maxisch.client.gui.widget.RowListWidget
import com.maxisch.client.gui.widget.TextLineWidget
import com.maxisch.paint.PalettePreset
import com.maxisch.paint.PresetStores
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block

/**
 * Edits the active palette: which donor blocks an area apply draws from, and how often each one
 * comes up. Weights are relative, so the row shows the share they work out to rather than the raw
 * number alone.
 */
class PaletteTab(private val screen: PainterScreen) : ApTab("austrianpainter.tab.palette") {

    private companion object {
        const val MARGIN = 8
        const val ROW_HEIGHT = 16
        const val BUTTON_HEIGHT = 20
        const val GAP = 4
        const val GREY = 0xFFA0A0A0.toInt()
        const val BAR = 0x80FFAA00.toInt()

        const val COARSE_STEP = 10
    }

    private var selected: Block? = null

    private val font get() = Minecraft.getInstance().font

    private val palette: PalettePreset get() = PresetStores.palettes.active

    private val headerLine = add(TextLineWidget(0, 0, 0, GREY))

    private val addButton = add(
        Button.builder(Component.translatable("austrianpainter.palette.add")) {
            Minecraft.getInstance().setScreenAndShow(BlockPickerScreen(screen) { block -> addDonor(block) })
        }.width(110).build(),
    )

    private val removeButton = add(
        Button.builder(Component.translatable("austrianpainter.palette.remove")) { removeDonor() }
            .width(100).build(),
    )

    private val list = add(
        RowListWidget<Block>(0, 0, 0, 0, ROW_HEIGHT).apply {
            emptyMessage = { Component.translatable("austrianpainter.palette.empty") }
            hoverTooltip = { weightHint() }
            isSelected = { it == selected }
            onRowClick = { block ->
                selected = block
                removeButton.active = true
            }
            // Wheel over a row edits that row's weight; anywhere else it scrolls the list.
            onRowScroll = { block, direction ->
                adjust(block, direction * if (shiftHeld()) COARSE_STEP else 1)
                true
            }
            drawRow = { graphics, block, x, y, _ ->
                val weight = palette.weights[block]
                if (weight != null) {
                    val total = palette.totalWeight().coerceAtLeast(1)
                    // A bar behind the row makes the mix readable without doing the arithmetic.
                    val barWidth = (width - 4) * weight / total
                    graphics.fill(x + 2, y + ROW_HEIGHT - 3, x + 2 + barWidth, y + ROW_HEIGHT - 1, BAR)

                    val stack = ItemStack(block)
                    if (!stack.isEmpty) graphics.item(stack, x + 2, y)

                    graphics.text(
                        font,
                        Component.translatable(
                            "austrianpainter.palette.row",
                            block.name,
                            weight,
                            weight * 100 / total,
                        ),
                        x + 22, y + 4, 0xFFFFFFFF.toInt(),
                    )
                }
            }
        },
    )

    init {
        addButton.setTooltip(Tooltip.create(weightHint()))
    }

    // ------------------------------------------------------------------ layout

    override fun doLayout(area: ScreenRectangle) {
        val x = area.left() + MARGIN
        val contentWidth = area.width() - MARGIN * 2
        var y = area.top() + GAP

        headerLine.setRectangle(contentWidth, TextLineWidget.HEIGHT, x, y)
        y += TextLineWidget.HEIGHT + GAP

        addButton.setRectangle(addButton.width, BUTTON_HEIGHT, x, y)
        removeButton.setRectangle(removeButton.width, BUTTON_HEIGHT, x + addButton.width + GAP, y)
        y += BUTTON_HEIGHT + GAP * 2

        val listHeight = (area.bottom() - y - GAP).coerceAtLeast(ROW_HEIGHT)
        list.setRectangle(contentWidth, listHeight, x, y)
    }

    // ------------------------------------------------------------------ mutation

    private fun addDonor(block: Block) {
        val added = palette.weights.putIfAbsent(block, PalettePreset.MIN_WEIGHT) == null
        selected = block
        save()
        screen.status(
            Component.translatable(
                if (added) "austrianpainter.palette.added" else "austrianpainter.palette.already",
                block.name,
            ),
        )
    }

    private fun removeDonor() {
        val block = selected ?: return
        if (palette.weights.remove(block) == null) return
        selected = null
        save()
        screen.status(Component.translatable("austrianpainter.palette.removed", block.name))
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

    override fun refresh() {
        list.rows = palette.weights.keys.toList()
        if (selected != null && selected !in list.rows) selected = null
        removeButton.active = selected != null
        headerLine.message = Component.translatable(
            "austrianpainter.palette.header",
            PresetStores.palettes.activeName,
            palette.size,
        )
    }

    /** A scroll carries no modifiers in 26.2, so ask the window directly. */
    private fun shiftHeld(): Boolean {
        val window = Minecraft.getInstance().window
        return InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT) ||
            InputConstants.isKeyDown(window, InputConstants.KEY_RSHIFT)
    }

    private fun weightHint(): Component =
        Component.translatable("austrianpainter.palette.weight_hint", COARSE_STEP)
}
