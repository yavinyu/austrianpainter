package com.maxisch.client.gui.screen

import com.maxisch.client.gui.widget.ActButtonWidget
import com.maxisch.client.gui.widget.ColorSwatchWidget
import com.maxisch.client.gui.widget.ToggleSwitchWidget
import com.maxisch.client.render.render2d.NVGUtils
import com.maxisch.paint.settings.ApSettings
import com.maxisch.paint.settings.ApSettings.FluidOverride
import com.maxisch.paint.ChunkRebuild
import com.maxisch.paint.preset.PresetStores
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import com.maxisch.client.gui.ApScreen
import com.maxisch.client.gui.Theme
import com.maxisch.client.gui.nvgSurface

/**
 * Boss-preset-specific override for the "Replace Fluid" settings, reached from the Dungeon
 * tab's editor row. Edits [ApSettings.setFluidOverride] for whichever boss preset is
 * currently active ([PresetStores.bosses.activeName]) - the same "always edits whatever's
 * active" model the P1/P2/P3 lists behind this screen already use for their own rules. While
 * the custom toggle is off, every field falls through to the client-wide values the Settings
 * tab's own "Replace Fluid" panel edits.
 *
 * A modal for the same reason [ColorPickerScreen] is one: the Dungeon tab's three columns
 * already use the whole tab area, so this has nowhere to live inline.
 */
class ReplaceFluidBossScreen(parent: Screen?) :
    ApScreen(Component.translatable("austrianpainter.settings.replace_fluid"), parent) {

    private companion object {
        const val PANEL_WIDTH = 260
        const val PANEL_HEIGHT = 236
        const val PAD = 12
        const val ROW = 20
        const val TOGGLE_W = 30
        const val TOGGLE_H = 14
        const val SWATCH = 18
        const val GAP = 8
        const val LABEL_GAP = 6
    }

    private val config get() = PresetStores.bosses.activeName

    private lateinit var customToggle: ToggleSwitchWidget
    private lateinit var waterAsLavaToggle: ToggleSwitchWidget
    private lateinit var lavaAsWaterToggle: ToggleSwitchWidget
    private lateinit var waterTintToggle: ToggleSwitchWidget
    private lateinit var waterTintSwatch: ColorSwatchWidget
    private lateinit var waterTintFlatToggle: ToggleSwitchWidget
    private lateinit var lavaTintToggle: ToggleSwitchWidget
    private lateinit var lavaTintSwatch: ColorSwatchWidget
    private lateinit var lavaTintFlatToggle: ToggleSwitchWidget

    private val rowLabels = mutableListOf<Pair<Component, Int>>()

    private val panelX get() = (width - PANEL_WIDTH) / 2
    private val panelY get() = (height - PANEL_HEIGHT) / 2

    override fun init() {
        rowLabels.clear()
        val contentX = panelX + PAD
        val labelX = contentX + TOGGLE_W + LABEL_GAP
        val contentWidth = PANEL_WIDTH - PAD * 2
        var y = panelY + PAD + 16

        fun label(text: String, textY: Int) {
            rowLabels.add(Component.translatable(text) to textY)
        }

        customToggle = addRenderableWidget(
            ToggleSwitchWidget(contentX, y, TOGGLE_W, TOGGLE_H, ApSettings.hasFluidOverride(config), Theme.AMBER) {
                toggleCustom()
            },
        )
        label("austrianpainter.settings.replace_fluid_custom_boss", y + 3)
        y += ROW + GAP

        waterAsLavaToggle = addRenderableWidget(
            ToggleSwitchWidget(contentX, y, TOGGLE_W, TOGGLE_H, ApSettings.effectiveWaterAsLava, Theme.AMBER) {
                edit { it.copy(waterAsLava = !ApSettings.effectiveWaterAsLava) }
            },
        )
        label("austrianpainter.settings.replace_fluid_water_as_lava", y + 3)
        y += ROW

        lavaAsWaterToggle = addRenderableWidget(
            ToggleSwitchWidget(contentX, y, TOGGLE_W, TOGGLE_H, ApSettings.effectiveLavaAsWater, Theme.AMBER) {
                edit { it.copy(lavaAsWater = !ApSettings.effectiveLavaAsWater) }
            },
        )
        label("austrianpainter.settings.replace_fluid_lava_as_water", y + 3)
        y += ROW + GAP

        waterTintToggle = addRenderableWidget(
            ToggleSwitchWidget(contentX, y, TOGGLE_W, TOGGLE_H, ApSettings.effectiveWaterTintEnabled, Theme.AMBER) {
                edit { it.copy(waterTintEnabled = !ApSettings.effectiveWaterTintEnabled) }
            },
        )
        label("austrianpainter.settings.replace_fluid_water_tint", y + 3)
        waterTintSwatch = addRenderableWidget(
            ColorSwatchWidget(contentX + contentWidth - SWATCH, y - (SWATCH - TOGGLE_H) / 2, SWATCH, SWATCH, ApSettings.effectiveWaterTintColor),
        ).also { swatch ->
            swatch.onPress = {
                Minecraft.getInstance().setScreenAndShow(
                    ColorPickerScreen(
                        this,
                        Component.translatable("austrianpainter.settings.pick_colour"),
                        ApSettings.effectiveWaterTintColor,
                    ) { argb ->
                        edit { it.copy(waterTintColor = argb) }
                        swatch.color = argb
                    },
                )
            }
        }
        y += ROW

        waterTintFlatToggle = addRenderableWidget(
            ToggleSwitchWidget(contentX, y, TOGGLE_W, TOGGLE_H, ApSettings.effectiveWaterTintFlat, Theme.AMBER) {
                edit { it.copy(waterTintFlat = !ApSettings.effectiveWaterTintFlat) }
            },
        )
        label("austrianpainter.settings.replace_fluid_tint_flat", y + 3)
        y += ROW

        lavaTintToggle = addRenderableWidget(
            ToggleSwitchWidget(contentX, y, TOGGLE_W, TOGGLE_H, ApSettings.effectiveLavaTintEnabled, Theme.AMBER) {
                edit { it.copy(lavaTintEnabled = !ApSettings.effectiveLavaTintEnabled) }
            },
        )
        label("austrianpainter.settings.replace_fluid_lava_tint", y + 3)
        lavaTintSwatch = addRenderableWidget(
            ColorSwatchWidget(contentX + contentWidth - SWATCH, y - (SWATCH - TOGGLE_H) / 2, SWATCH, SWATCH, ApSettings.effectiveLavaTintColor),
        ).also { swatch ->
            swatch.onPress = {
                Minecraft.getInstance().setScreenAndShow(
                    ColorPickerScreen(
                        this,
                        Component.translatable("austrianpainter.settings.pick_colour"),
                        ApSettings.effectiveLavaTintColor,
                    ) { argb ->
                        edit { it.copy(lavaTintColor = argb) }
                        swatch.color = argb
                    },
                )
            }
        }
        y += ROW

        lavaTintFlatToggle = addRenderableWidget(
            ToggleSwitchWidget(contentX, y, TOGGLE_W, TOGGLE_H, ApSettings.effectiveLavaTintFlat, Theme.AMBER) {
                edit { it.copy(lavaTintFlat = !ApSettings.effectiveLavaTintFlat) }
            },
        )
        label("austrianpainter.settings.replace_fluid_tint_flat", y + 3)
        y += ROW + GAP

        addRenderableWidget(
            ActButtonWidget.builder(Component.translatable("gui.done")) { onClose() }
                .bounds(contentX, panelY + PANEL_HEIGHT - PAD - ROW, contentWidth, ROW)
                .build(),
        )

        refreshEnabled()
    }

    /** Turns the whole override bundle on (seeded from the current effective values, so
     *  flipping it on changes nothing visible) or off (drops the bucket entirely, falling
     *  back to the client-wide values). */
    private fun toggleCustom() {
        if (ApSettings.hasFluidOverride(config)) {
            ApSettings.setFluidOverride(config) { FluidOverride() }
        } else {
            ApSettings.setFluidOverride(config) {
                FluidOverride(
                    waterAsLava = ApSettings.effectiveWaterAsLava,
                    lavaAsWater = ApSettings.effectiveLavaAsWater,
                    waterTintEnabled = ApSettings.effectiveWaterTintEnabled,
                    waterTintColor = ApSettings.effectiveWaterTintColor,
                    waterTintFlat = ApSettings.effectiveWaterTintFlat,
                    lavaTintEnabled = ApSettings.effectiveLavaTintEnabled,
                    lavaTintColor = ApSettings.effectiveLavaTintColor,
                    lavaTintFlat = ApSettings.effectiveLavaTintFlat,
                )
            }
        }
        customToggle.checked = ApSettings.hasFluidOverride(config)
        ChunkRebuild.markAll()
        refreshEnabled()
        refreshValues()
    }

    private fun edit(update: (FluidOverride) -> FluidOverride) {
        ApSettings.setFluidOverride(config, update)
        ChunkRebuild.markAll()
        refreshValues()
    }

    private fun refreshEnabled() {
        val on = ApSettings.hasFluidOverride(config)
        for (widget in listOf(
            waterAsLavaToggle, lavaAsWaterToggle,
            waterTintToggle, lavaTintToggle, waterTintSwatch, lavaTintSwatch,
            waterTintFlatToggle, lavaTintFlatToggle,
        )) {
            widget.active = on
        }
    }

    private fun refreshValues() {
        waterAsLavaToggle.checked = ApSettings.effectiveWaterAsLava
        lavaAsWaterToggle.checked = ApSettings.effectiveLavaAsWater
        waterTintToggle.checked = ApSettings.effectiveWaterTintEnabled
        lavaTintToggle.checked = ApSettings.effectiveLavaTintEnabled
        waterTintSwatch.color = ApSettings.effectiveWaterTintColor
        lavaTintSwatch.color = ApSettings.effectiveLavaTintColor
        waterTintFlatToggle.checked = ApSettings.effectiveWaterTintFlat
        lavaTintFlatToggle.checked = ApSettings.effectiveLavaTintFlat
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        drawPanel(graphics)
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        graphics.text(font, title, panelX + PAD, panelY + PAD, Theme.INK)
        for ((text, textY) in rowLabels) {
            graphics.text(font, text, panelX + PAD + TOGGLE_W + LABEL_GAP, textY, Theme.INK)
        }
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
