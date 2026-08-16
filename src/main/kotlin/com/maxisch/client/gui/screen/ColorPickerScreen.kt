package com.maxisch.client.gui.screen

import com.maxisch.client.gui.widget.ActButtonWidget
import com.maxisch.client.gui.widget.ColorPickerWidget
import com.maxisch.client.render.render2d.NVGUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import com.maxisch.client.gui.widget.ApEditBox
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import com.maxisch.client.gui.ApScreen
import com.maxisch.client.gui.Theme
import com.maxisch.client.gui.nvgSurface

/**
 * Modal wrapper around [ColorPickerWidget], reached by clicking a swatch in the Settings tab.
 *
 * A modal rather than an inline panel because the Settings tab has four colour rows and already lays
 * out more content than its area holds - four inline pickers would not fit at any GUI scale. It also
 * fixes a bug in the old inline hex field on its own: that saved to disk from the `EditBox`
 * responder, so typing eight digits wrote the whole settings JSON up to eight times. Here nothing is
 * written until Done.
 */
class ColorPickerScreen(
    parent: Screen?,
    title: Component,
    private val initial: Int,
    private val onCommit: (Int) -> Unit,
) : ApScreen(title, parent) {

    private companion object {
        const val PANEL_WIDTH = 240
        const val PANEL_HEIGHT = 200
        const val PAD = 12
        const val PICKER_HEIGHT = 110
        const val ROW = 20
        const val GAP = 6

        /** Shown in the hex box's own colour when the text cannot be parsed. */
        val INVALID = Theme.RED
    }

    private var current = initial
    private lateinit var picker: ColorPickerWidget
    private lateinit var hex: EditBox

    /** Guards the two-way binding: the picker writes the box, whose responder would write back. */
    private var syncing = false

    private val panelX get() = (width - PANEL_WIDTH) / 2
    private val panelY get() = (height - PANEL_HEIGHT) / 2

    override fun init() {
        val contentX = panelX + PAD
        val contentWidth = PANEL_WIDTH - PAD * 2

        picker = addRenderableWidget(
            ColorPickerWidget(contentX, panelY + PAD + 14, contentWidth, PICKER_HEIGHT, initial) { argb ->
                current = argb
                syncing = true
                hex.value = "%08X".format(argb)
                syncing = false
            },
        )

        hex = addRenderableWidget(
            ApEditBox(font, contentX, panelY + PAD + 14 + PICKER_HEIGHT + GAP, contentWidth, ROW, title),
        )
        hex.setMaxLength(9)
        hex.value = "%08X".format(initial)
        hex.setResponder { text ->
            if (syncing) return@setResponder
            val parsed = text.trim().removePrefix("#").toUIntOrNull(16)?.toInt()
            // Invalid input is shown, not swallowed: the old field returned silently, so a typo left
            // the colour unchanged with nothing on screen to say why.
            hex.setTextColor(if (parsed == null) INVALID else Theme.INK)
            if (parsed != null) {
                current = parsed
                picker.setColor(parsed)
            }
        }

        val buttonY = panelY + PANEL_HEIGHT - PAD - ROW
        val buttonWidth = (contentWidth - GAP) / 2
        addRenderableWidget(
            ActButtonWidget(
                contentX,
                buttonY,
                buttonWidth,
                ROW,
                Component.translatable("gui.cancel"),
                ActButtonWidget.Variant.GHOST,
            ) { onClose() },
        )
        addRenderableWidget(
            ActButtonWidget(
                contentX + buttonWidth + GAP,
                buttonY,
                buttonWidth,
                ROW,
                Component.translatable("gui.done"),
                ActButtonWidget.Variant.PRIMARY,
            ) {
                onCommit(current)
                onClose()
            },
        )
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        drawPanel(graphics)
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        graphics.text(font, title, panelX + PAD, panelY + PAD, Theme.INK)
    }

    /** Drawn before `super`, so the widgets registered above land on top of it rather than under. */
    private fun drawPanel(graphics: GuiGraphicsExtractor) {
        val left = panelX
        val top = panelY
        nvgSurface(
            graphics, left - 1, top - 1, PANEL_WIDTH + 2, PANEL_HEIGHT + 2,
            nvg = {
                NVGUtils.drawDropShadow(left.toFloat(), top.toFloat(), PANEL_WIDTH.toFloat(), PANEL_HEIGHT.toFloat(), 8f, 2f, Theme.RADIUS)
                NVGUtils.drawRect(left.toFloat(), top.toFloat(), PANEL_WIDTH.toFloat(), PANEL_HEIGHT.toFloat(), Theme.RADIUS, Theme.c(Theme.SURFACE))
                NVGUtils.drawOutlineRect(
                    left + Theme.OUTLINE_INSET,
                    top + Theme.OUTLINE_INSET,
                    PANEL_WIDTH - Theme.OUTLINE_THICKNESS,
                    PANEL_HEIGHT - Theme.OUTLINE_THICKNESS,
                    Theme.RADIUS,
                    Theme.OUTLINE_THICKNESS,
                    Theme.c(Theme.RULE),
                )
            },
            vanilla = {
                graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, Theme.SURFACE)
                graphics.outline(left, top, PANEL_WIDTH, PANEL_HEIGHT, Theme.RULE)
            },
        )
    }
}
