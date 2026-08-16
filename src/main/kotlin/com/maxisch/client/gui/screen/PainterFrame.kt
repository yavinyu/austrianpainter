package com.maxisch.client.gui.screen

import com.maxisch.client.keybind.KeyHints
import com.maxisch.client.keybind.PaintKeys
import com.maxisch.client.gui.widget.ToolRailWidget
import com.maxisch.client.render.render2d.NVGUtils
import com.maxisch.paint.settings.ApSettings
import com.maxisch.paint.PaintSession
import com.maxisch.paint.session.PaintBrush
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import kotlin.math.roundToInt
import com.maxisch.client.gui.Theme
import com.maxisch.client.gui.nvgSurface

/**
 * The paint screen's frame: the armed-state bar across the top and the status strip along the
 * bottom. The tool rail is [ToolRailWidget], which has to be its own widget because it takes clicks.
 *
 * Donor, scope and radius are global state in this mod - they survive tab switches and they decide
 * what every action does - but they only ever appeared in the world HUD, which is hidden behind this
 * screen. Putting them in the frame means the tool always says what is loaded.
 *
 * Non-interactive by construction ([active] is cleared), so it never eats a click meant for the tab
 * underneath. Register it before every other widget: renderables draw in registration order, and
 * this is the ground the rest of the frame sits on.
 */
class PainterFrame(
    /** Left edge of the region the scrim covers; the rail paints its own column opaque. */
    private val contentLeft: Int = ToolRailWidget.WIDTH,
    /** The picker overlay reuses the armed bar but brings its own footer. */
    private val withFooter: Boolean = true,
    /**
     * Which half this instance draws.
     *
     * The frame is registered twice: the ground first, under everything, and the chrome last, over
     * everything. That order is what lets a tab scroll - content that runs past the top or bottom of
     * its area slides under the bar and the footer instead of over them, which is what a scroll
     * container is supposed to look like.
     */
    private val part: Part = Part.BOTH,
    private val status: () -> Component? = { null },
) : AbstractWidget(0, 0, 0, 0, CommonComponents.EMPTY) {

    enum class Part { GROUND, CHROME, BOTH }

    companion object {
        /**
         * The mockup's proportions - 12.6% and 8.5% of its 960x540 canvas.
         *
         * Worth having when the screen can pay for them, and it usually can: at GUI scale 2 on a
         * 1080p display the tabs still get 390px. On a short screen they cannot, which is what the
         * compact pair below is for.
         */
        private const val ARMED_TALL = 68
        private const val FOOTER_TALL = 46

        /** The tightest that still holds two text lines over a rule, and one button row. */
        private const val ARMED_COMPACT = 52
        private const val FOOTER_COMPACT = 34

        /**
         * Below this the frame goes compact.
         *
         * The Settings tab needs roughly 410px of content height, and it is the tallest in the mod;
         * anything shorter than this leaves it clipped no matter how the chrome is sized, so the
         * chrome may as well give back what it can.
         */
        private const val TALL_SCREEN = 500

        var armedHeight = ARMED_COMPACT
            private set

        var footerHeight = FOOTER_COMPACT
            private set

        /**
         * Picks the height pair for [screenHeight]. Called from layout, before anything is placed.
         *
         * Static rather than per-instance because the picker screen builds its own frame and every
         * tab's `doLayout` reads these - they describe the shell, not one widget.
         */
        fun measure(screenHeight: Int) {
            val tall = screenHeight >= TALL_SCREEN
            armedHeight = if (tall) ARMED_TALL else ARMED_COMPACT
            footerHeight = if (tall) FOOTER_TALL else FOOTER_COMPACT
        }

        /** Kept as the name every caller already uses; resolves to whichever pair is in force. */
        val ARMED_HEIGHT: Int get() = armedHeight
        val FOOTER_HEIGHT: Int get() = footerHeight

        const val PAD_X = 12

        /** The two-line label/value block, centred in whichever bar height is in force. */
        private val LABEL_Y: Int get() = (armedHeight - 20) / 2
        private val VALUE_Y: Int get() = LABEL_Y + 11

        private const val CHIP = 16
        private const val SLOT_GAP = 14
        private const val SEPARATOR_INSET = 10

        private const val PILL_HEIGHT = 13
        private const val PILL_PAD_X = 6
        private const val PILL_GAP = 6

        private val ARMED_PILL_BACKGROUND = Theme.withAlpha(Theme.GREEN, 0x12)
        private val ARMED_PILL_BORDER = Theme.withAlpha(Theme.GREEN, 0x59)

        /**
         * The ground behind the tab content, at the mockup scrim's 72%.
         *
         * Not opaque on purpose: this screen does not pause the game
         * ([ApScreen.isPauseScreen]) so that paint can be judged against a moving scene, and a solid
         * panel would take that away. 72% is enough for the shell to read as one surface while the
         * world stays legible behind it.
         */
        private val CONTENT_GROUND = Theme.withAlpha(Theme.GROUND, 0xB8)
    }

    init {
        active = false
    }

    private val font get() = Minecraft.getInstance().font

    /** Where the footer's own widgets may be placed, given the screen height. */
    fun footerTop(screenHeight: Int): Int =
        if (withFooter) screenHeight - FOOTER_HEIGHT else screenHeight

    // ------------------------------------------------------------------ state read off the mod

    private fun donor() = PaintBrush.donor

    private fun donorName(): Component =
        donor()?.name ?: Component.translatable("austrianpainter.brush.no_donor")

    private fun scopeName(): Component =
        PaintSession.scope?.let { Component.literal(it.key) }
            ?: Component.translatable("austrianpainter.frame.world")

    private fun armedPill(): Component = if (PaintBrush.enabled) {
        Component.translatable("austrianpainter.frame.armed")
    } else {
        Component.translatable("austrianpainter.frame.disarmed")
    }

    // ------------------------------------------------------------------ render

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val screenWidth = width
        val footerY = footerTop(height)

        if (part != Part.CHROME) drawContentGround(graphics, screenWidth, footerY)
        if (part != Part.GROUND) {
            drawArmedBar(graphics, screenWidth)
            if (withFooter) drawFooter(graphics, screenWidth, footerY)
        }
    }

    /** Fills the area the tabs lay out into, so the shell is not a window onto the raw world. */
    private fun drawContentGround(graphics: GuiGraphicsExtractor, screenWidth: Int, footerY: Int) {
        val left = contentLeft
        val contentWidth = (screenWidth - left).coerceAtLeast(0)
        val contentHeight = (footerY - ARMED_HEIGHT).coerceAtLeast(0)
        if (contentWidth == 0 || contentHeight == 0) return

        nvgSurface(
            graphics, left, ARMED_HEIGHT, contentWidth, contentHeight,
            nvg = {
                NVGUtils.drawRect(
                    left.toFloat(),
                    ARMED_HEIGHT.toFloat(),
                    contentWidth.toFloat(),
                    contentHeight.toFloat(),
                    Theme.c(CONTENT_GROUND),
                )
            },
            vanilla = {
                graphics.fill(left, ARMED_HEIGHT, left + contentWidth, ARMED_HEIGHT + contentHeight, CONTENT_GROUND)
            },
        )
    }

    private fun drawArmedBar(graphics: GuiGraphicsExtractor, screenWidth: Int) {
        val donorStack = donor()?.let { ItemStack(it) }?.takeUnless { it.isEmpty }

        // Everything is measured before anything is drawn: the chrome goes down in one NanoVG pass
        // and the text in a vanilla pass after it, so both passes need the same numbers.
        val wordmark = Component.translatable("austrianpainter.frame.wordmark")
        val subtitle = Component.literal(ApSettings.activePalette)

        // Measured with the renderer that draws them, tracking included: mixing NanoVG text with
        // Font.width puts every separator a few pixels off, and the error compounds along the bar.
        fun labelWidth(text: Component) =
            Theme.textWidth(text.string, Theme.TEXT_SIZE_SMALL, tracking = Theme.LABEL_TRACKING)

        fun valueWidth(text: Component) = Theme.textWidth(text.string)

        var cursor = PAD_X +
            maxOf(Theme.textWidth(wordmark.string, Theme.TEXT_SIZE_LARGE), labelWidth(subtitle)).toInt() + SLOT_GAP
        val slots = mutableListOf<Slot>()
        val separators = mutableListOf<Int>()

        fun slot(label: Component, value: Component, valueColour: Int, chip: Boolean = false) {
            separators += cursor
            cursor += SLOT_GAP
            val chipWidth = if (chip) CHIP + 6 else 0
            slots += Slot(cursor, label, value, valueColour, chip)
            cursor += chipWidth + maxOf(labelWidth(label), valueWidth(value)).toInt() + SLOT_GAP
        }

        slot(
            Component.translatable("austrianpainter.frame.donor"),
            donorName(),
            if (donor() == null) Theme.DIM else Theme.AMBER,
            chip = true,
        )
        slot(Component.translatable("austrianpainter.frame.scope"), scopeName(), Theme.INK)
        slot(
            Component.translatable("austrianpainter.frame.radius"),
            Component.literal(PaintBrush.radius.toString()),
            Theme.INK,
        )

        val keyPill = KeyHints.name(PaintKeys.openMenuKey)
        val statePill = armedPill()
        val keyPillWidth = labelWidth(keyPill).toInt() + PILL_PAD_X * 2
        val statePillWidth = labelWidth(statePill).toInt() + PILL_PAD_X * 2
        val keyPillX = screenWidth - PAD_X - keyPillWidth
        val statePillX = keyPillX - PILL_GAP - statePillWidth
        val pillY = (ARMED_HEIGHT - PILL_HEIGHT) / 2
        val armed = PaintBrush.enabled

        nvgSurface(
            graphics, 0, 0, screenWidth, ARMED_HEIGHT,
            nvg = {
                NVGUtils.drawRect(0f, 0f, screenWidth.toFloat(), ARMED_HEIGHT.toFloat(), Theme.c(Theme.SURFACE))
                NVGUtils.drawRect(
                    0f,
                    ARMED_HEIGHT - Theme.OUTLINE_THICKNESS,
                    screenWidth.toFloat(),
                    Theme.OUTLINE_THICKNESS,
                    Theme.c(Theme.RULE),
                )

                separators.forEach { separatorX ->
                    NVGUtils.drawRect(
                        separatorX.toFloat(),
                        SEPARATOR_INSET.toFloat(),
                        Theme.OUTLINE_THICKNESS,
                        (ARMED_HEIGHT - SEPARATOR_INSET * 2).toFloat(),
                        Theme.c(Theme.RULE),
                    )
                }

                slots.filter { it.chip }.forEach { chipSlot ->
                    val chipY = (ARMED_HEIGHT - CHIP) / 2f
                    NVGUtils.drawRect(
                        chipSlot.x.toFloat(),
                        chipY,
                        CHIP.toFloat(),
                        CHIP.toFloat(),
                        Theme.RADIUS_SMALL,
                        Theme.c(Theme.GROUND),
                    )
                    // An armed donor gets the amber border; an empty slot stays a plain well.
                    NVGUtils.drawOutlineRect(
                        chipSlot.x + Theme.OUTLINE_INSET,
                        chipY + Theme.OUTLINE_INSET,
                        CHIP - Theme.OUTLINE_THICKNESS,
                        CHIP - Theme.OUTLINE_THICKNESS,
                        Theme.RADIUS_SMALL,
                        Theme.OUTLINE_THICKNESS,
                        Theme.c(if (donorStack == null) Theme.RULE else Theme.AMBER),
                    )
                }

                pill(statePillX, pillY, statePillWidth, armed)
                pill(keyPillX, pillY, keyPillWidth, false)
            },
            vanilla = {
                graphics.fill(0, 0, screenWidth, ARMED_HEIGHT, Theme.SURFACE)
                graphics.fill(0, ARMED_HEIGHT - 1, screenWidth, ARMED_HEIGHT, Theme.RULE)
                separators.forEach { separatorX ->
                    graphics.fill(separatorX, SEPARATOR_INSET, separatorX + 1, ARMED_HEIGHT - SEPARATOR_INSET, Theme.RULE)
                }
                slots.filter { it.chip }.forEach { chipSlot ->
                    val chipY = (ARMED_HEIGHT - CHIP) / 2
                    graphics.fill(chipSlot.x, chipY, chipSlot.x + CHIP, chipY + CHIP, Theme.GROUND)
                    graphics.outline(chipSlot.x, chipY, CHIP, CHIP, if (donorStack == null) Theme.RULE else Theme.AMBER)
                }
                graphics.outline(statePillX, pillY, statePillWidth, PILL_HEIGHT, if (armed) ARMED_PILL_BORDER else Theme.RULE)
                graphics.outline(keyPillX, pillY, keyPillWidth, PILL_HEIGHT, Theme.RULE)
            },
        )

        // A second surface for the text, over the chrome laid down above. The labels are a size down
        // from their values, which is what makes a slot read as one thing rather than two lines.
        nvgSurface(
            graphics, 0, 0, screenWidth, ARMED_HEIGHT,
            nvg = {
                val pillTextY = (pillY + (PILL_HEIGHT - Theme.textHeight(Theme.TEXT_SIZE_SMALL)) / 2f).roundToInt()
                text(wordmark, PAD_X, LABEL_Y - 2, Theme.INK, Theme.TEXT_SIZE_LARGE)
                text(subtitle, PAD_X, VALUE_Y, Theme.DIM, Theme.TEXT_SIZE_SMALL)
                slots.forEach { slotEntry ->
                    val textX = slotEntry.x + if (slotEntry.chip) CHIP + 6 else 0
                    text(slotEntry.label, textX, LABEL_Y, Theme.DIM, Theme.TEXT_SIZE_SMALL)
                    text(slotEntry.value, textX, VALUE_Y, slotEntry.valueColour)
                }
                text(statePill, statePillX + PILL_PAD_X, pillTextY, if (armed) Theme.GREEN else Theme.MUTED, Theme.TEXT_SIZE_SMALL)
                text(keyPill, keyPillX + PILL_PAD_X, pillTextY, Theme.MUTED, Theme.TEXT_SIZE_SMALL)
            },
            vanilla = {
                val pillTextY = pillY + (PILL_HEIGHT - font.lineHeight) / 2 + 1
                graphics.text(font, wordmark, PAD_X, LABEL_Y, Theme.INK)
                graphics.text(font, subtitle, PAD_X, VALUE_Y, Theme.DIM)
                slots.forEach { slotEntry ->
                    val textX = slotEntry.x + if (slotEntry.chip) CHIP + 6 else 0
                    graphics.text(font, slotEntry.label, textX, LABEL_Y, Theme.DIM)
                    graphics.text(font, slotEntry.value, textX, VALUE_Y, slotEntry.valueColour)
                }
                graphics.text(font, statePill, statePillX + PILL_PAD_X, pillTextY, if (armed) Theme.GREEN else Theme.MUTED)
                graphics.text(font, keyPill, keyPillX + PILL_PAD_X, pillTextY, Theme.MUTED)
            },
        )

        // The donor's model stays vanilla whatever happens - NanoVG cannot draw an item - and is
        // drawn last so it sits above both surfaces.
        slots.firstOrNull { it.chip }?.let { chipSlot ->
            if (donorStack != null) graphics.item(donorStack, chipSlot.x, (ARMED_HEIGHT - CHIP) / 2)
        }
    }

    /**
     * One NanoVG string, in the frame's own type scale.
     *
     * Anything at [Theme.TEXT_SIZE_SMALL] here is an uppercase label - DONOR, SCOPE, the pills - so
     * it gets the tracking automatically rather than every call site remembering.
     */
    private fun text(message: Component, x: Int, y: Int, colour: Int, size: Float = Theme.TEXT_SIZE) {
        val tracking = if (size == Theme.TEXT_SIZE_SMALL) Theme.LABEL_TRACKING else 0f
        NVGUtils.drawText(message.string, x.toFloat(), y.toFloat(), size, Theme.c(colour), Theme.body, tracking)
    }

    /** A rounded outline chip; [live] gives it the green the armed state uses. */
    private fun pill(x: Int, y: Int, w: Int, live: Boolean) {
        val radius = PILL_HEIGHT / 2f
        if (live) {
            NVGUtils.drawRect(x.toFloat(), y.toFloat(), w.toFloat(), PILL_HEIGHT.toFloat(), radius, Theme.c(ARMED_PILL_BACKGROUND))
        }
        NVGUtils.drawOutlineRect(
            x + Theme.OUTLINE_INSET,
            y + Theme.OUTLINE_INSET,
            w - Theme.OUTLINE_THICKNESS,
            PILL_HEIGHT - Theme.OUTLINE_THICKNESS,
            radius,
            Theme.OUTLINE_THICKNESS,
            Theme.c(if (live) ARMED_PILL_BORDER else Theme.RULE),
        )
    }

    private fun drawFooter(graphics: GuiGraphicsExtractor, screenWidth: Int, footerY: Int) {
        nvgSurface(
            graphics, 0, footerY, screenWidth, FOOTER_HEIGHT,
            nvg = {
                NVGUtils.drawRect(0f, footerY.toFloat(), screenWidth.toFloat(), FOOTER_HEIGHT.toFloat(), Theme.c(Theme.SURFACE))
                NVGUtils.drawRect(0f, footerY.toFloat(), screenWidth.toFloat(), Theme.OUTLINE_THICKNESS, Theme.c(Theme.RULE))
            },
            vanilla = {
                graphics.fill(0, footerY, screenWidth, footerY + FOOTER_HEIGHT, Theme.SURFACE)
                graphics.fill(0, footerY, screenWidth, footerY + 1, Theme.RULE)
            },
        )

        val message = status() ?: return
        nvgSurface(
            graphics, 0, footerY, screenWidth, FOOTER_HEIGHT,
            nvg = {
                val statusY = (footerY + (FOOTER_HEIGHT - Theme.textHeight()) / 2f).roundToInt()
                text(message, PAD_X, statusY, Theme.GREEN)
            },
            vanilla = {
                val statusY = footerY + (FOOTER_HEIGHT - font.lineHeight) / 2
                graphics.text(font, message, PAD_X, statusY, Theme.GREEN)
            },
        )
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit

    /** One armed-state readout: a label over a value, optionally preceded by the donor's item chip. */
    private data class Slot(
        val x: Int,
        val label: Component,
        val value: Component,
        val valueColour: Int,
        val chip: Boolean,
    )
}
