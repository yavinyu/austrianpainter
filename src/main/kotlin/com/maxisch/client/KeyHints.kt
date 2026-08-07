package com.maxisch.client

import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component

/**
 * Names keybinds in prose the player reads.
 *
 * Every hint is built from the live [KeyMapping], never from a glyph baked into the English string:
 * a rebound key would otherwise leave the UI telling the player to press something that does
 * nothing.
 */
object KeyHints {

    fun name(mapping: KeyMapping?): Component = when {
        mapping == null || mapping.isUnbound -> Component.translatable("austrianpainter.key.unbound")
        else -> mapping.translatedKeyMessage
    }

    /** "Hold X and scroll to resize the brush" - the one gesture nothing else advertises. */
    fun resizeHint(): Component =
        Component.translatable("austrianpainter.brush.resize_hint", name(PaintKeys.brushPaintKey))

    fun resizeTooltip(): Tooltip = Tooltip.create(resizeHint())

    /** "Set both corners with X and Y". */
    fun cornerHint(): Component = Component.translatable(
        "austrianpainter.area.incomplete",
        name(PaintKeys.cornerFirstKey),
        name(PaintKeys.cornerSecondKey),
    )
}
