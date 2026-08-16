package com.maxisch.client.gui.screen

import com.maxisch.client.gui.widget.ActButtonWidget
import com.maxisch.client.render.render2d.NVGUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.ceil
import com.maxisch.client.gui.ApScreen
import com.maxisch.client.gui.Theme
import com.maxisch.client.gui.nvgSurface

/**
 * The shell's own yes/no gate, replacing vanilla's [net.minecraft.client.gui.screens.ConfirmScreen].
 *
 * Reused by [ConfirmAction] for every destructive one-click button - clearing painted blocks has no
 * undo path large enough to cover it, see `PaintHistory.MAX_POSITIONS_PER_STEP`. Either choice
 * returns to [parent]; [onResult] only runs `true` on confirm.
 *
 * The message is wrapped with vanilla's own `Font.split`, not NanoVG's `nvgTextBox`: line *breaking*
 * only needs to be close enough to fit the panel, and every glyph is still drawn and measured by
 * NanoVG per line - unlike the tooltip's tight picture-in-picture clip, this panel is a fixed,
 * generous width, so a few pixels of wrap disagreement costs nothing.
 */
class ApConfirmScreen(
    parent: Screen,
    title: Component,
    private val message: Component,
    private val onResult: (Boolean) -> Unit,
) : ApScreen(title, parent) {

    private companion object {
        const val PANEL_WIDTH = 280
        const val PAD = 16
        const val ROW = 20
        const val GAP = 8
        const val TITLE_GAP = 10
        const val LINE_GAP = 3
    }

    private lateinit var lines: List<String>
    private var lineHeight = 0
    private var panelHeight = 0

    private val panelX get() = (width - PANEL_WIDTH) / 2
    private val panelY get() = (height - panelHeight) / 2

    override fun init() {
        val contentWidth = PANEL_WIDTH - PAD * 2

        lines = font.split(message, contentWidth).map { sequence ->
            val line = StringBuilder()
            sequence.accept { _, _, codePoint -> line.appendCodePoint(codePoint); true }
            line.toString()
        }
        lineHeight = ceil(Theme.textHeight()).toInt() + LINE_GAP
        val titleHeight = ceil(Theme.textHeight(Theme.TEXT_SIZE_LARGE)).toInt()
        val messageHeight = lines.size * lineHeight
        panelHeight = PAD + titleHeight + TITLE_GAP + messageHeight + GAP + ROW + PAD

        val contentX = panelX + PAD
        val buttonWidth = (contentWidth - GAP) / 2
        val buttonY = panelY + panelHeight - PAD - ROW

        addRenderableWidget(
            ActButtonWidget(
                contentX,
                buttonY,
                buttonWidth,
                ROW,
                Component.translatable("gui.no"),
                ActButtonWidget.Variant.GHOST,
            ) { finish(false) },
        )
        addRenderableWidget(
            ActButtonWidget(
                contentX + buttonWidth + GAP,
                buttonY,
                buttonWidth,
                ROW,
                Component.translatable("gui.yes"),
                ActButtonWidget.Variant.DANGER,
            ) { finish(true) },
        )
    }

    private fun finish(confirmed: Boolean) {
        onResult(confirmed)
        onClose()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        drawPanel(graphics)
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    /** Drawn before `super`, so the widgets registered in [init] land on top of it rather than under. */
    private fun drawPanel(graphics: GuiGraphicsExtractor) {
        val left = panelX
        val top = panelY
        val contentX = left + PAD

        nvgSurface(
            graphics, left - 1, top - 1, PANEL_WIDTH + 2, panelHeight + 2,
            nvg = {
                NVGUtils.drawDropShadow(left.toFloat(), top.toFloat(), PANEL_WIDTH.toFloat(), panelHeight.toFloat(), 8f, 2f, Theme.RADIUS)
                NVGUtils.drawRect(left.toFloat(), top.toFloat(), PANEL_WIDTH.toFloat(), panelHeight.toFloat(), Theme.RADIUS, Theme.c(Theme.SURFACE))
                NVGUtils.drawOutlineRect(
                    left + Theme.OUTLINE_INSET,
                    top + Theme.OUTLINE_INSET,
                    PANEL_WIDTH - Theme.OUTLINE_THICKNESS,
                    panelHeight - Theme.OUTLINE_THICKNESS,
                    Theme.RADIUS,
                    Theme.OUTLINE_THICKNESS,
                    Theme.c(Theme.RED),
                )

                var textY = top + PAD.toFloat()
                NVGUtils.drawText(title.string, contentX.toFloat(), textY, Theme.TEXT_SIZE_LARGE, Theme.c(Theme.INK), Theme.bold)
                textY += ceil(Theme.textHeight(Theme.TEXT_SIZE_LARGE)) + TITLE_GAP
                lines.forEach { line ->
                    NVGUtils.drawText(line, contentX.toFloat(), textY, Theme.TEXT_SIZE, Theme.c(Theme.TEXT_DIM), Theme.body)
                    textY += lineHeight
                }
            },
            vanilla = {
                graphics.fill(left, top, left + PANEL_WIDTH, top + panelHeight, Theme.SURFACE)
                graphics.outline(left, top, PANEL_WIDTH, panelHeight, Theme.RED)
                graphics.text(font, title, contentX, top + PAD, Theme.INK)
                var textY = top + PAD + ceil(Theme.textHeight(Theme.TEXT_SIZE_LARGE)).toInt() + TITLE_GAP
                lines.forEach { line ->
                    graphics.text(font, Component.literal(line), contentX, textY, Theme.TEXT_DIM)
                    textY += lineHeight
                }
            },
        )
    }
}
