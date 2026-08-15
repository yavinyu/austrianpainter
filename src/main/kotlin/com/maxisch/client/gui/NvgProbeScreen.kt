package com.maxisch.client.gui

import com.maxisch.client.render.render2d.NVGUtils
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks

/**
 * A throwaway harness that answers one question: does a NanoVG rectangle land exactly where the
 * identical vanilla `fill` lands, at every GUI scale?
 *
 * The first attempt at this port died on the coordinate transform in
 * [com.maxisch.client.render.render2d.NvgBridge], and it was never confirmed against a real frame -
 * the widgets were simply written and hoped for. This screen is the confirmation step, and it is meant
 * to be deleted once the answer is recorded.
 *
 * Read it like this:
 *
 * - **Alignment pairs** - each row draws a vanilla fill and a NanoVG rect of identical bounds, one on
 *   top of the other. If the transform is right the NanoVG rect covers its vanilla twin exactly and
 *   the magenta ground disappears. Any magenta edge is the error, in GUI pixels, at that position.
 * - **Corners** - four rects hard against the screen edges. Catches a transform that is correct near
 *   the origin and drifts with distance, which a single centred rect would hide.
 * - **Layering** - an item model drawn after an intersecting NanoVG rect. It must appear above it;
 *   that is what lets the row lists mix NanoVG chrome with vanilla block icons.
 * - **Readout** - GUI scale, window size and device pixel ratio, so a screenshot is self-describing.
 *
 * Open with `/paintbrush nvgprobe`, then change GUI scale and resize the window without closing it.
 */
class NvgProbeScreen(parent: Screen?) : ApScreen(Component.literal("NanoVG probe"), parent) {

    private companion object {
        /** Shows through wherever the NanoVG rect fails to cover the vanilla one beneath it. */
        const val GROUND = 0xFFFF00FF.toInt()
        const val NVG_FILL = 0xFF00A0FF.toInt()
        const val LABEL = 0xFFFFFFFF.toInt()
        const val GOOD = 0xFF55FF55.toInt()

        const val CORNER = 24
        const val ROW_HEIGHT = 22
        const val ROW_WIDTH = 160
    }

    /**
     * Changes GUI scale in place.
     *
     * Without this the probe is close to unusable for the one thing it exists to test: the vanilla
     * way to change scale is the options screen, which replaces this one, and coming back means
     * retyping the command. Keys 1/2/3 set that scale, 0 sets Auto.
     */
    override fun keyPressed(event: KeyEvent): Boolean {
        val scale = when (event.key) {
            InputConstants.KEY_0, InputConstants.KEY_NUMPAD0 -> 0
            InputConstants.KEY_1, InputConstants.KEY_NUMPAD1 -> 1
            InputConstants.KEY_2, InputConstants.KEY_NUMPAD2 -> 2
            InputConstants.KEY_3, InputConstants.KEY_NUMPAD3 -> 3
            else -> return super.keyPressed(event)
        }
        minecraft.options.guiScale().set(scale)
        // Applies the option and re-inits this screen at the new scale, the same path the options
        // screen takes when its slider moves.
        minecraft.resizeGui()
        return true
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        val left = 20
        var y = 40

        // Sub-pixel positions on purpose: a transform that only works on even coordinates is a
        // transform that will misplace half the widgets in the real UI.
        val rows = listOf(0 to 0, 1 to 0, 0 to 1, 3 to 7)

        rows.forEach { (dx, dy) ->
            val x = left + dx
            val rowY = y + dy
            graphics.fill(x, rowY, x + ROW_WIDTH, rowY + ROW_HEIGHT, GROUND)
            nvgSurface(
                graphics, x, rowY, ROW_WIDTH, ROW_HEIGHT,
                nvg = {
                    NVGUtils.drawRect(x.toFloat(), rowY.toFloat(), ROW_WIDTH.toFloat(), ROW_HEIGHT.toFloat(), Theme.c(NVG_FILL))
                },
                vanilla = {
                    graphics.fill(x, rowY, x + ROW_WIDTH, rowY + ROW_HEIGHT, NVG_FILL)
                },
            )
            graphics.text(font, Component.literal("offset +$dx,+$dy - no magenta = exact"), x + ROW_WIDTH + 8, rowY + 7, LABEL)
            y += ROW_HEIGHT + 6
        }

        corners(graphics)
        layering(graphics, left, y + 16)
        fonts(graphics, left, y + 44)
        readout(graphics, left, y + 100)
    }

    /**
     * The same string in both renderers, stacked, so which one is actually drawing is not a matter
     * of opinion about letterforms.
     *
     * Also prints each renderer's measured width of it: those two numbers must never be mixed in a
     * layout, and seeing them disagree is the point.
     */
    private fun fonts(graphics: GuiGraphicsExtractor, x: Int, y: Int) {
        val sample = "AaBbGg 0123 — the quick brown fox"

        graphics.text(font, Component.literal("vanilla: $sample"), x, y, LABEL)

        nvgSurface(
            graphics, x, y + 12, 400, 14,
            nvg = {
                NVGUtils.drawText("nanovg:  $sample", x.toFloat(), (y + 12).toFloat(), Theme.TEXT_SIZE, Theme.c(GOOD), Theme.body)
            },
            // If this branch is what renders, NanoVG text is unavailable and the line says so.
            vanilla = {
                graphics.text(font, Component.literal("nanovg:  UNAVAILABLE (vanilla fallback)"), x, y + 12, GROUND)
            },
        )

        val nvgWidth = Theme.textWidth(sample)
        val vanillaWidth = font.width(sample)
        graphics.text(
            font,
            Component.literal("width - vanilla $vanillaWidth px, nanovg $nvgWidth px, ready=${NVGUtils.isReady()}"),
            x,
            y + 26,
            LABEL,
        )
    }

    /** Catches drift that only shows far from the origin. */
    private fun corners(graphics: GuiGraphicsExtractor) {
        listOf(
            0 to 0,
            width - CORNER to 0,
            0 to height - CORNER,
            width - CORNER to height - CORNER,
        ).forEach { (x, y) ->
            graphics.fill(x, y, x + CORNER, y + CORNER, GROUND)
            nvgSurface(
                graphics, x, y, CORNER, CORNER,
                nvg = {
                    NVGUtils.drawRect(x.toFloat(), y.toFloat(), CORNER.toFloat(), CORNER.toFloat(), Theme.c(NVG_FILL))
                },
                vanilla = { graphics.fill(x, y, x + CORNER, y + CORNER, NVG_FILL) },
            )
        }
    }

    /** The item must land above the NanoVG rect it overlaps. */
    private fun layering(graphics: GuiGraphicsExtractor, x: Int, y: Int) {
        nvgSurface(
            graphics, x, y, 64, 24,
            nvg = { NVGUtils.drawRect(x.toFloat(), y.toFloat(), 64f, 24f, Theme.c(NVG_FILL)) },
            vanilla = { graphics.fill(x, y, x + 64, y + 24, NVG_FILL) },
        )
        graphics.item(ItemStack(Blocks.STONE), x + 4, y + 4)
        graphics.item(ItemStack(Blocks.GOLD_BLOCK), x + 24, y + 4)
        graphics.text(font, Component.literal("both items must sit above the blue"), x + 72, y + 8, LABEL)
    }

    private fun readout(graphics: GuiGraphicsExtractor, x: Int, y: Int) {
        val window = minecraft.window
        val lines = listOf(
            "gui scale ${window.guiScale} (option ${minecraft.options.guiScale().get()})",
            "window ${window.width}x${window.height} framebuffer, ${window.screenWidth}x${window.screenHeight} screen",
            "screen ${width}x$height gui px",
            "NVGUtils.guiScale ${NVGUtils.guiScale()}, devicePixelRatio ${NVGUtils.devicePixelRatio()}",
            if (NVGUtils.isDisabled()) "NanoVG DISABLED - every rect above is the vanilla fallback" else "NanoVG active",
        )
        lines.forEachIndexed { index, line ->
            graphics.text(font, Component.literal(line), x, y + index * 11, if (NVGUtils.isDisabled()) GROUND else GOOD)
        }
    }
}
