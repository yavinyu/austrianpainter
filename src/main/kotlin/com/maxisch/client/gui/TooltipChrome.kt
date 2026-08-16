package com.maxisch.client.gui

import com.maxisch.client.render.render2d.NVGUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The tooltip background, in the shell's own style.
 *
 * Vanilla's is a purple-bordered box with a gradient, which is the last piece of another design
 * language left in this UI once the fields stopped drawing their own borders. This draws the same
 * shape as a card instead: rounded, surface fill, hairline rule, and a shadow, since a tooltip is
 * the one thing here that genuinely floats above everything else.
 *
 * Applied by [com.maxisch.mixin.client.TooltipBackgroundMixin], which is why [appliesTo] is public -
 * the mixin has to answer "is this one of ours" before cancelling vanilla's drawing.
 */
object TooltipChrome {

    private const val SHADOW_BLUR = 6f
    private const val SHADOW_SPREAD = 1f

    /**
     * True while one of this mod's screens is open.
     *
     * Restyling every tooltip in the game would be a mod doing something it was not asked to do; a
     * tooltip over a chest is not this mod's business.
     */
    fun appliesTo(): Boolean = Minecraft.getInstance().screen is ApScreen

    /**
     * One line of tooltip text, drawn by
     * [com.maxisch.mixin.client.TooltipTextMixin] in place of vanilla's.
     *
     * The surface is sized generously around the line rather than to it: the caller gives a baseline
     * and no bounds, and a picture-in-picture element clips to the rect it is given.
     */
    fun drawText(graphics: GuiGraphicsExtractor, line: String, x: Int, y: Int) {
        // This picture-in-picture surface hard-clips at its own edge (NvgBridge.renderToTexture
        // sizes the render target exactly to it), and NanoVG's measured advance width does not
        // perfectly match what it rasterises - the gap is small per glyph but adds up over a long
        // line. +2 was not enough slack and clipped the last character or two of longer tooltips.
        val width = kotlin.math.ceil(Theme.textWidth(line)).toInt() + 8
        // Matches the per-line height TooltipHeightMixin reports to vanilla's own box-height math,
        // so this surface has exactly as much room as the box built around it.
        val height = kotlin.math.ceil(Theme.textHeight()).toInt() + 6
        nvgSurface(
            graphics, x, y - 2, width, height,
            nvg = {
                NVGUtils.drawText(line, x.toFloat(), y.toFloat(), Theme.TEXT_SIZE, Theme.c(Theme.INK), Theme.body)
            },
            vanilla = {
                graphics.text(Minecraft.getInstance().font, net.minecraft.network.chat.Component.literal(line), x, y, Theme.INK)
            },
        )
    }

    fun draw(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) {
        nvgSurface(
            graphics, x - 4, y - 4, width + 8, height + 8,
            nvg = {
                NVGUtils.drawDropShadow(
                    x.toFloat(),
                    y.toFloat(),
                    width.toFloat(),
                    height.toFloat(),
                    SHADOW_BLUR,
                    SHADOW_SPREAD,
                    Theme.RADIUS,
                )
                NVGUtils.drawRect(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), Theme.RADIUS, Theme.c(Theme.SURFACE))
                NVGUtils.drawOutlineRect(
                    x + Theme.OUTLINE_INSET,
                    y + Theme.OUTLINE_INSET,
                    width - Theme.OUTLINE_THICKNESS,
                    height - Theme.OUTLINE_THICKNESS,
                    Theme.RADIUS,
                    Theme.OUTLINE_THICKNESS,
                    Theme.c(Theme.RULE),
                )
            },
            vanilla = {
                graphics.fill(x, y, x + width, y + height, Theme.SURFACE)
                graphics.outline(x, y, width, height, Theme.RULE)
            },
        )
    }
}
