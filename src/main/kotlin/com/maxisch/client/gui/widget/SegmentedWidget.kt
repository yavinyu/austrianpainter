package com.maxisch.client.gui.widget

import com.maxisch.client.gui.Theme
import com.maxisch.client.gui.nvgSurface
import com.maxisch.client.render.render2d.NVGUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

/**
 * A row of mutually exclusive options in one bordered box, the selected one lit amber.
 *
 * Replaces the cycling button this codebase uses for small enums - `Mode: Position`, `Shape: solid`,
 * `Seed: per column`. A cycler hides every option but the current one and gives no clue how many
 * there are or what comes next; the whole set is worth the same width here.
 */
class SegmentedWidget(
    private val options: List<Component>,
    private val selectedIndex: () -> Int,
    private val onSelect: (Int) -> Unit,
) : AbstractWidget(0, 0, 0, HEIGHT, CommonComponents.EMPTY) {

    companion object {
        const val HEIGHT = 16
    }

    private val font get() = net.minecraft.client.Minecraft.getInstance().font

    private fun indexAt(mouseX: Double): Int? {
        if (options.isEmpty() || mouseX < x || mouseX > x + width) return null
        return (((mouseX - x) / segmentWidth).toInt()).coerceIn(0, options.lastIndex)
    }

    private val segmentWidth get() = width.toFloat() / options.size.coerceAtLeast(1)

    override fun onClick(event: MouseButtonEvent, doubled: Boolean) {
        indexAt(event.x)?.let(onSelect)
    }

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val selected = selectedIndex()
        val hovered = if (isHovered) indexAt(mouseX.toDouble()) else null

        nvgSurface(
            graphics, x, y, width, height,
            nvg = {
                NVGUtils.drawRect(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), Theme.RADIUS_SMALL, Theme.c(Theme.GROUND))

                options.indices.forEach { index ->
                    val left = x + segmentWidth * index
                    if (index == selected) {
                        NVGUtils.drawRect(left, y.toFloat(), segmentWidth, height.toFloat(), Theme.RADIUS_SMALL, Theme.c(Theme.HOVER))
                    } else if (index == hovered) {
                        NVGUtils.drawRect(left, y.toFloat(), segmentWidth, height.toFloat(), Theme.RADIUS_SMALL, Theme.c(Theme.HOVER_TINT))
                    }
                    // Divider before every segment but the first, so the box reads as one control
                    // split up rather than as a row of separate boxes.
                    if (index > 0) {
                        NVGUtils.drawRect(left, (y + 2).toFloat(), Theme.OUTLINE_THICKNESS, (height - 4).toFloat(), Theme.c(Theme.RULE))
                    }
                }

                NVGUtils.drawOutlineRect(
                    x + Theme.OUTLINE_INSET,
                    y + Theme.OUTLINE_INSET,
                    width - Theme.OUTLINE_THICKNESS,
                    height - Theme.OUTLINE_THICKNESS,
                    Theme.RADIUS_SMALL,
                    Theme.OUTLINE_THICKNESS,
                    Theme.c(Theme.RULE),
                )

                val labelY = y + (height - Theme.textHeight(Theme.TEXT_SIZE_SMALL)) / 2f
                options.forEachIndexed { index, option ->
                    NVGUtils.drawCenteredText(
                        option.string,
                        x + segmentWidth * (index + 0.5f),
                        labelY,
                        Theme.TEXT_SIZE_SMALL,
                        Theme.c(if (index == selected) Theme.AMBER else Theme.MUTED),
                        Theme.body,
                    )
                }
            },
            vanilla = {
                graphics.fill(x, y, x + width, y + height, Theme.GROUND)
                options.indices.forEach { index ->
                    val left = x + (segmentWidth * index).toInt()
                    val right = x + (segmentWidth * (index + 1)).toInt()
                    if (index == selected) graphics.fill(left, y, right, y + height, Theme.HOVER)
                    if (index > 0) graphics.fill(left, y + 2, left + 1, y + height - 2, Theme.RULE)
                }
                graphics.outline(x, y, width, height, Theme.RULE)
                options.forEachIndexed { index, option ->
                    graphics.centeredText(
                        font,
                        option,
                        x + (segmentWidth * (index + 0.5f)).toInt(),
                        y + (height - font.lineHeight) / 2 + 1,
                        if (index == selected) Theme.AMBER else Theme.MUTED,
                    )
                }
            },
        )
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
}
