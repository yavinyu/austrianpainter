package com.maxisch.client.gui.widget

import com.maxisch.client.gui.Theme
import com.maxisch.client.gui.nvgSurface
import com.maxisch.client.render.render2d.Colour
import com.maxisch.client.render.render2d.Gradient
import com.maxisch.client.render.render2d.NVGUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.CommonComponents

/**
 * Saturation/value square, hue strip and alpha slider - the first colour picker in this mod's own UI.
 *
 * Until now the only way to pick a colour here was to type eight hex digits, or to leave the mod
 * entirely for YACL's picker behind ModMenu. The three controls are drawn with the gradient
 * primitives the NanoVG port brought in; nothing here is possible with vanilla's `fill`.
 *
 * State is HSVA rather than ARGB because that is what the controls address directly. Round-tripping
 * through ARGB on every drag would lose the hue of a fully desaturated colour - drag value to zero
 * and back and the hue would come back red instead of where it was left.
 */
class ColorPickerWidget(
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    argb: Int,
    private val onChange: (Int) -> Unit,
) : AbstractWidget(x, y, w, h, CommonComponents.EMPTY) {

    private companion object {
        const val HUE_WIDTH = 14
        const val ALPHA_HEIGHT = 14
        const val GAP = 6

        /**
         * `nvgLinearGradient` takes two stops, so a full hue ramp cannot be one call. Six 60-degree
         * segments is the smallest split whose endpoints are all primary or secondary - each segment
         * interpolates one channel and stays true to the wheel. rsm sidesteps this with a hue PNG;
         * image support was deliberately stripped from this port.
         */
        const val HUE_SEGMENTS = 6

        const val KNOB_RADIUS = 4f
        const val CHECKER = 4
        val CHECKER_LIGHT = Colour.of(0xFF808080.toInt())
        val CHECKER_DARK = Colour.of(0xFF505050.toInt())
        val WHITE = Colour.of(0xFFFFFFFF.toInt())
        val BLACK = Colour.of(0xFF000000.toInt())
        val TRANSPARENT = Colour.of(0x00000000)
    }

    /** Which control the current drag started on; a drag keeps its control even if it leaves it. */
    private enum class Region { NONE, SQUARE, HUE, ALPHA }

    private var hue = 0f
    private var saturation = 0f
    private var value = 0f
    private var alpha = 1f
    private var dragging = Region.NONE

    init {
        setColor(argb)
    }

    // ------------------------------------------------------------------ geometry

    private val squareWidth get() = width - HUE_WIDTH - GAP
    private val squareHeight get() = height - ALPHA_HEIGHT - GAP
    private val hueX get() = x + squareWidth + GAP
    private val alphaY get() = y + squareHeight + GAP

    // ------------------------------------------------------------------ colour

    /** Adopts [argb], keeping the current hue when the colour is too grey to imply one. */
    fun setColor(argb: Int) {
        val red = (argb shr 16 and 0xFF) / 255f
        val green = (argb shr 8 and 0xFF) / 255f
        val blue = (argb and 0xFF) / 255f
        alpha = (argb ushr 24 and 0xFF) / 255f

        val max = maxOf(red, green, blue)
        val min = minOf(red, green, blue)
        val delta = max - min

        value = max
        saturation = if (max == 0f) 0f else delta / max
        if (delta != 0f) {
            hue = when (max) {
                red -> ((green - blue) / delta + if (green < blue) 6f else 0f) / 6f
                green -> ((blue - red) / delta + 2f) / 6f
                else -> ((red - green) / delta + 4f) / 6f
            }
        }
    }

    fun color(): Int = hsvaToArgb(hue, saturation, value, alpha)

    private fun hsvaToArgb(h: Float, s: Float, v: Float, a: Float): Int {
        val sector = ((h % 1f + 1f) % 1f) * 6f
        val index = sector.toInt()
        val fraction = sector - index
        val p = v * (1f - s)
        val q = v * (1f - s * fraction)
        val t = v * (1f - s * (1f - fraction))

        val (red, green, blue) = when (index % 6) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return (a.toByte255() shl 24) or (red.toByte255() shl 16) or (green.toByte255() shl 8) or blue.toByte255()
    }

    private fun Float.toByte255(): Int = Math.round(this.coerceIn(0f, 1f) * 255f)

    /** The pure hue behind the square, at full saturation and value. */
    private fun hueColour(h: Float): Colour = Theme.c(hsvaToArgb(h, 1f, 1f, 1f))

    // ------------------------------------------------------------------ input

    override fun onClick(event: MouseButtonEvent, doubled: Boolean) {
        dragging = regionAt(event.x, event.y)
        apply(event.x, event.y)
    }

    /**
     * Keeps editing the control the drag began on.
     *
     * Dragging out of the square and back is normal use - the pointer leaves it constantly near the
     * edges - and re-testing the region here would hand the drag to whichever control it strayed over.
     */
    override fun onDrag(event: MouseButtonEvent, dragX: Double, dragY: Double) {
        apply(event.x, event.y)
    }

    override fun onRelease(event: MouseButtonEvent) {
        dragging = Region.NONE
    }

    private fun regionAt(mouseX: Double, mouseY: Double): Region = when {
        mouseY >= alphaY -> Region.ALPHA
        mouseX >= hueX -> Region.HUE
        else -> Region.SQUARE
    }

    private fun apply(mouseX: Double, mouseY: Double) {
        when (dragging) {
            Region.SQUARE -> {
                saturation = ((mouseX - x) / squareWidth).toFloat().coerceIn(0f, 1f)
                value = 1f - ((mouseY - y) / squareHeight).toFloat().coerceIn(0f, 1f)
            }
            Region.HUE -> hue = ((mouseY - y) / squareHeight).toFloat().coerceIn(0f, 1f)
            Region.ALPHA -> alpha = ((mouseX - x) / width.toFloat()).toFloat().coerceIn(0f, 1f)
            Region.NONE -> return
        }
        onChange(color())
    }

    // ------------------------------------------------------------------ render

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        nvgSurface(
            graphics, x, y, width, height,
            nvg = {
                drawSquare()
                drawHueStrip()
                drawAlphaSlider()
                drawKnobs()
            },
            // No vanilla fallback worth the name: `fill` cannot draw a gradient, and approximating
            // one as a column of strips would be slower and uglier than saying so. The hex box beside
            // this widget stays usable, which is exactly the pre-NanoVG state of affairs.
            vanilla = {
                graphics.fill(x, y, x + width, y + height, Theme.GROUND)
                graphics.outline(x, y, width, height, Theme.OUTLINE)
                graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, color())
            },
        )
    }

    /** White to hue across, then transparent to black down - the standard SV square, in two calls. */
    private fun drawSquare() {
        val left = x.toFloat()
        val top = y.toFloat()
        val w = squareWidth.toFloat()
        val h = squareHeight.toFloat()

        NVGUtils.drawGradientRect(left, top, w, h, Theme.RADIUS_SMALL, WHITE, hueColour(hue), Gradient.LeftToRight)
        NVGUtils.drawGradientRect(left, top, w, h, Theme.RADIUS_SMALL, TRANSPARENT, BLACK, Gradient.TopToBottom)
        outline(left, top, w, h)
    }

    private fun drawHueStrip() {
        val segment = squareHeight.toFloat() / HUE_SEGMENTS
        repeat(HUE_SEGMENTS) { index ->
            val from = hueColour(index / HUE_SEGMENTS.toFloat())
            val to = hueColour((index + 1) / HUE_SEGMENTS.toFloat())
            NVGUtils.drawGradientRect(
                hueX.toFloat(),
                y + index * segment,
                HUE_WIDTH.toFloat(),
                segment,
                0f,
                from,
                to,
                Gradient.TopToBottom,
            )
        }
        outline(hueX.toFloat(), y.toFloat(), HUE_WIDTH.toFloat(), squareHeight.toFloat())
    }

    /** Checkerboard first, so the transparent end of the ramp reads as transparent and not as black. */
    private fun drawAlphaSlider() {
        val top = alphaY.toFloat()
        NVGUtils.pushScissor(x.toFloat(), top, width.toFloat(), ALPHA_HEIGHT.toFloat())
        var checkerX = x
        var row = 0
        while (checkerX < x + width) {
            var checkerY = alphaY
            row++
            var column = row
            while (checkerY < alphaY + ALPHA_HEIGHT) {
                NVGUtils.drawRect(
                    checkerX.toFloat(),
                    checkerY.toFloat(),
                    CHECKER.toFloat(),
                    CHECKER.toFloat(),
                    if (column++ % 2 == 0) CHECKER_LIGHT else CHECKER_DARK,
                )
                checkerY += CHECKER
            }
            checkerX += CHECKER
        }
        NVGUtils.popScissor()

        val opaque = Theme.c(hsvaToArgb(hue, saturation, value, 1f))
        NVGUtils.drawGradientRect(
            x.toFloat(),
            top,
            width.toFloat(),
            ALPHA_HEIGHT.toFloat(),
            Theme.RADIUS_SMALL,
            opaque.alpha(0f),
            opaque,
            Gradient.LeftToRight,
        )
        outline(x.toFloat(), top, width.toFloat(), ALPHA_HEIGHT.toFloat())
    }

    /**
     * Each knob is a white ring over a black one.
     *
     * A single-colour knob vanishes against part of its own control - a white ring is invisible on
     * the white corner of the square, a black one on the black edge - and every gradient here has
     * both extremes in it.
     */
    private fun drawKnobs() {
        knob(x + saturation * squareWidth, y + (1f - value) * squareHeight)
        knob(hueX + HUE_WIDTH / 2f, y + hue * squareHeight)
        knob(x + alpha * width, alphaY + ALPHA_HEIGHT / 2f)
    }

    private fun knob(cx: Float, cy: Float) {
        NVGUtils.drawCircle(cx, cy, KNOB_RADIUS + 1f, Theme.c(0xFF000000.toInt()))
        NVGUtils.drawCircle(cx, cy, KNOB_RADIUS, Theme.c(Theme.INK))
        NVGUtils.drawCircle(cx, cy, KNOB_RADIUS - 1.5f, Theme.c(color() or 0xFF000000.toInt()))
    }

    private fun outline(left: Float, top: Float, w: Float, h: Float) {
        NVGUtils.drawOutlineRect(
            left + Theme.OUTLINE_INSET,
            top + Theme.OUTLINE_INSET,
            w - Theme.OUTLINE_THICKNESS,
            h - Theme.OUTLINE_THICKNESS,
            Theme.RADIUS_SMALL,
            Theme.OUTLINE_THICKNESS,
            Theme.c(Theme.OUTLINE),
        )
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
}
