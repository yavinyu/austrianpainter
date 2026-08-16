package com.maxisch.client.gui.widget

import com.maxisch.client.gui.Theme
import com.maxisch.client.gui.nvgSurface
import com.maxisch.client.render.render2d.NVGUtils
import com.maxisch.mixin.client.EditBoxAccessor
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * An [EditBox] drawn entirely by this mod: the shell's flat well, and its text in the shipped font.
 *
 * Only the drawing is replaced. Caret movement, selection, clipboard, IME preedit, the max length,
 * the responder - all of it stays vanilla's, which is the half worth keeping and the half that is
 * genuinely hard. What vanilla draws is a bitmap-font string, and a field showing bitmap glyphs
 * among NanoVG labels is the most obvious remaining giveaway that this UI is two renderers.
 *
 * The caret, the selection anchor and the horizontal scroll are private with no getters, so they
 * come through [EditBoxAccessor]. Reading them is what keeps the drawn text and vanilla's idea of
 * the caret in agreement.
 */
class ApEditBox(
    font: Font,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    message: Component,
) : EditBox(font, x, y, width, height, message) {

    private companion object {
        const val PAD = 4

        /** Milliseconds per caret blink phase; vanilla blinks on a tick counter at about this rate. */
        const val BLINK_MS = 600L

        const val CARET_WIDTH = 1f
    }

    /** Mirrors of vanilla's private fields, kept because the setters are the only way in. */
    private var textColour = Theme.INK
    private var hintText: Component? = null

    init {
        isBordered = false
    }

    override fun setTextColor(colour: Int) {
        textColour = colour
        super.setTextColor(colour)
    }

    override fun setHint(hint: Component) {
        hintText = hint
        super.setHint(hint)
    }

    private val cursor get() = (this as EditBoxAccessor).`austrianpainter$cursorPos`()
    private val highlight get() = (this as EditBoxAccessor).`austrianpainter$highlightPos`()
    private val scroll get() = (this as EditBoxAccessor).`austrianpainter$displayPos`()

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        if (!visible) return

        val border = if (isFocused) Theme.AMBER else Theme.RULE
        val innerWidth = width - PAD * 2
        val textX = (x + PAD).toFloat()
        val textY = y + (height - Theme.textHeight()) / 2f

        // Vanilla scrolls by character index; everything below is measured from that first visible
        // character, so a long value scrolls exactly as vanilla decided it should.
        val full = value
        val from = scroll.coerceIn(0, full.length)
        val visibleText = fit(full.substring(from), innerWidth.toFloat())

        val selectionStart = minOf(cursor, highlight).coerceAtLeast(from) - from
        val selectionEnd = maxOf(cursor, highlight).coerceAtMost(from + visibleText.length) - from
        val caretOffset = (cursor - from).coerceIn(0, visibleText.length)

        nvgSurface(
            graphics, x, y, width, height,
            nvg = {
                NVGUtils.drawRect(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), Theme.RADIUS_SMALL, Theme.c(Theme.GROUND))
                NVGUtils.drawOutlineRect(
                    x + Theme.OUTLINE_INSET,
                    y + Theme.OUTLINE_INSET,
                    width - Theme.OUTLINE_THICKNESS,
                    height - Theme.OUTLINE_THICKNESS,
                    Theme.RADIUS_SMALL,
                    Theme.OUTLINE_THICKNESS,
                    Theme.c(border),
                )

                if (selectionEnd > selectionStart) {
                    val left = textX + Theme.textWidth(visibleText.take(selectionStart))
                    val right = textX + Theme.textWidth(visibleText.take(selectionEnd))
                    NVGUtils.drawRect(left, (y + 2).toFloat(), right - left, (height - 4).toFloat(), Theme.c(Theme.SELECTED))
                }

                if (full.isEmpty()) {
                    hintText?.let {
                        NVGUtils.drawText(it.string, textX, textY, Theme.TEXT_SIZE, Theme.c(Theme.DIM), Theme.body)
                    }
                } else {
                    NVGUtils.drawText(visibleText, textX, textY, Theme.TEXT_SIZE, Theme.c(textColour), Theme.body)
                }

                // A bar rather than vanilla's underscore-when-at-the-end: the well is tall enough for
                // one, and it does not shift the text the way an appended glyph would.
                if (isFocused && (System.currentTimeMillis() / BLINK_MS) % 2 == 0L) {
                    val caretX = textX + Theme.textWidth(visibleText.take(caretOffset))
                    NVGUtils.drawRect(caretX, (y + 3).toFloat(), CARET_WIDTH, (height - 6).toFloat(), Theme.c(Theme.INK))
                }
            },
            // Without NanoVG this is vanilla's own field again, border and all - the point of the
            // fallback is that the UI stays usable, not that it stays pretty.
            vanilla = {
                graphics.fill(x, y, x + width, y + height, Theme.GROUND)
                graphics.outline(x, y, width, height, border)
                val pose = graphics.pose()
                pose.pushMatrix()
                pose.translate(PAD.toFloat(), 0f)
                super.extractWidgetRenderState(graphics, mouseX - PAD, mouseY, partialTick)
                pose.popMatrix()
            },
        )
    }

    /** The longest prefix of [text] that fits [available], so a long value clips instead of bleeding. */
    private fun fit(text: String, available: Float): String {
        if (Theme.textWidth(text) <= available) return text
        var end = text.length
        while (end > 0 && Theme.textWidth(text.substring(0, end)) > available) end--
        return text.substring(0, end)
    }

    /** Undoes the [PAD] the text is drawn at, so a click lands the caret under the pointer. */
    override fun onClick(event: MouseButtonEvent, doubled: Boolean) {
        super.onClick(MouseButtonEvent(event.x - PAD, event.y, event.buttonInfo()), doubled)
    }
}
