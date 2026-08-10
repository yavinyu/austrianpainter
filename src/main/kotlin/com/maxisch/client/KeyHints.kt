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

    /** "Look at a block and press X to erase it" - the only way to unpaint one position. */
    fun eraseHint(): Component =
        Component.translatable("austrianpainter.brush.erase_hint", name(PaintKeys.brushEraseKey))

    fun eraseTooltip(): Tooltip = Tooltip.create(eraseHint())

    /** "Undo (N) - X", so the footer button names the key that does the same thing. */
    fun undoTooltip(): Tooltip =
        Tooltip.create(Component.translatable("austrianpainter.undo.hint", name(PaintKeys.undoKey)))

    /** "X clears the box" - the clear-selection key is otherwise advertised nowhere. */
    fun clearAreaTooltip(): Tooltip =
        Tooltip.create(Component.translatable("austrianpainter.area.clear_hint", name(PaintKeys.clearAreaKey)))

    fun redoTooltip(): Tooltip =
        Tooltip.create(Component.translatable("austrianpainter.redo.hint", name(PaintKeys.redoKey)))

    fun overlayTooltip(): Tooltip = Tooltip.create(
        Component.translatable("austrianpainter.overlay.hint", name(PaintKeys.toggleOverlayKey)),
    )
}
