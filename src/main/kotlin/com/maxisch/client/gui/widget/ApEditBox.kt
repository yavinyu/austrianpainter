package com.maxisch.client.gui.widget

import com.maxisch.client.gui.Theme
import com.maxisch.client.gui.nvgSurface
import com.maxisch.client.render.render2d.NVGUtils
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * An [EditBox] drawn as the shell's flat well instead of vanilla's raised box.
 *
 * Vanilla's border is the single most obvious leftover once everything around it is drawn by NanoVG:
 * a hard white rectangle among rounded, hairline-ruled cards. This keeps every behaviour that makes
 * an edit box worth using - caret, selection, clipboard, IME, scrolling - and replaces only the
 * chrome, which is why it subclasses rather than reimplements.
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
        /** Breathing room between the well's edge and the first glyph. */
        const val PAD = 4
    }

    init {
        // Vanilla draws its background and border only when bordered; unbordered it draws just the
        // text, caret and selection - which is exactly the half worth keeping.
        isBordered = false
    }

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val border = if (isFocused) Theme.AMBER else Theme.RULE

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
            },
            vanilla = {
                graphics.fill(x, y, x + width, y + height, Theme.GROUND)
                graphics.outline(x, y, width, height, border)
            },
        )

        // Unbordered, vanilla starts the text hard against getX(). Shifting the draw rather than the
        // widget keeps every layout in the mod unchanged; [onClick] shifts the input to match, so
        // clicking still lands the caret between the characters actually under the pointer.
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(PAD.toFloat(), 0f)
        super.extractWidgetRenderState(graphics, mouseX - PAD, mouseY, partialTick)
        pose.popMatrix()
    }

    /** Undoes the render-side [PAD] shift so the caret lands where the glyphs are drawn. */
    override fun onClick(event: MouseButtonEvent, doubled: Boolean) {
        super.onClick(MouseButtonEvent(event.x - PAD, event.y, event.buttonInfo()), doubled)
    }
}
