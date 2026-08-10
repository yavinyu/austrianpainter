package com.maxisch.client

import com.maxisch.client.gui.PainterScreen
import com.maxisch.client.render.PaintedOverlay
import com.maxisch.paint.ApSettings
import com.maxisch.paint.PaintStorage
import com.maxisch.paint.session.PaintArea
import com.maxisch.paint.session.PaintBrush
import com.maxisch.paint.session.PaintSelection
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW

/**
 * Keybinds are deliberately raycast-only: painting never touches the block and never sends a
 * packet, so the mod stays invisible to any server.
 */
object PaintKeys {

    private val CATEGORY = KeyMapping.Category.register(Identifier.parse("austrianpainter:main"))

    private lateinit var openMenu: KeyMapping
    private lateinit var brushPaint: KeyMapping
    private lateinit var brushErase: KeyMapping
    private lateinit var openArea: KeyMapping
    private lateinit var cornerFirst: KeyMapping
    private lateinit var cornerSecond: KeyMapping
    private lateinit var clearArea: KeyMapping
    private lateinit var undo: KeyMapping
    private lateinit var redo: KeyMapping
    private lateinit var toggleOverlay: KeyMapping

    fun register() {
        openMenu = bind("key.austrianpainter.open", GLFW.GLFW_KEY_P)
        brushPaint = bind("key.austrianpainter.brush_paint", GLFW.GLFW_KEY_G)
        brushErase = bind("key.austrianpainter.brush_erase", GLFW.GLFW_KEY_H)
        openArea = bind("key.austrianpainter.open_area", GLFW.GLFW_KEY_O)
        cornerFirst = bind("key.austrianpainter.corner_first", GLFW.GLFW_KEY_LEFT_BRACKET)
        cornerSecond = bind("key.austrianpainter.corner_second", GLFW.GLFW_KEY_RIGHT_BRACKET)
        // Next to the two corner keys, since it undoes them.
        clearArea = bind("key.austrianpainter.clear_area", GLFW.GLFW_KEY_BACKSLASH)
        // Z is unbound in vanilla, and it is where undo lives everywhere else.
        undo = bind("key.austrianpainter.undo", GLFW.GLFW_KEY_Z)
        // Next to undo, the way Y sits next to Z on a QWERTZ board - and unbound in vanilla too.
        redo = bind("key.austrianpainter.redo", GLFW.GLFW_KEY_Y)
        toggleOverlay = bind("key.austrianpainter.toggle_overlay", GLFW.GLFW_KEY_J)

        ClientTickEvents.END_CLIENT_TICK.register { client -> tick(client) }
    }

    /** True while the paint key is held; the scroll hook uses this to resize the brush. */
    @JvmStatic
    fun isBrushKeyDown(): Boolean =
        ApSettings.keybindsEnabled && ::brushPaint.isInitialized && brushPaint.isDown

    // Read by [KeyHints] so prose can name the live binding. Null before registration, which only
    // happens during client init - every reader runs long after that, but the guard is free.

    val openMenuKey: KeyMapping? get() = if (::openMenu.isInitialized) openMenu else null
    val brushPaintKey: KeyMapping? get() = if (::brushPaint.isInitialized) brushPaint else null
    val brushEraseKey: KeyMapping? get() = if (::brushErase.isInitialized) brushErase else null
    val openAreaKey: KeyMapping? get() = if (::openArea.isInitialized) openArea else null
    val cornerFirstKey: KeyMapping? get() = if (::cornerFirst.isInitialized) cornerFirst else null
    val cornerSecondKey: KeyMapping? get() = if (::cornerSecond.isInitialized) cornerSecond else null
    val clearAreaKey: KeyMapping? get() = if (::clearArea.isInitialized) clearArea else null
    val undoKey: KeyMapping? get() = if (::undo.isInitialized) undo else null
    val redoKey: KeyMapping? get() = if (::redo.isInitialized) redo else null
    val toggleOverlayKey: KeyMapping? get() = if (::toggleOverlay.isInitialized) toggleOverlay else null

    private fun bind(translationKey: String, key: Int): KeyMapping =
        KeyMappingHelper.registerKeyMapping(
            KeyMapping(translationKey, InputConstants.Type.KEYSYM, key, CATEGORY),
        )

    private fun tick(client: Minecraft) {
        if (!ApSettings.keybindsEnabled) {
            drainClicks()
            return
        }

        while (brushPaint.consumeClick()) stroke(client, paint = true)
        while (brushErase.consumeClick()) stroke(client, paint = false)
        while (cornerFirst.consumeClick()) markCorner(client, first = true)
        while (cornerSecond.consumeClick()) markCorner(client, first = false)
        while (clearArea.consumeClick()) clearSelection(client)
        while (toggleOverlay.consumeClick()) flipOverlay(client)
        while (undo.consumeClick()) {
            if (client.level != null) tell(client, PaintStorage.undo())
        }
        while (redo.consumeClick()) {
            if (client.level != null) tell(client, PaintStorage.redo())
        }
        while (openMenu.consumeClick()) {
            if (client.level != null) client.setScreenAndShow(PainterScreen.open())
        }
        // The dedicated area key still exists, it just lands on the area tab now.
        while (openArea.consumeClick()) {
            if (client.level != null) {
                client.setScreenAndShow(PainterScreen.openAt(PainterScreen.TabId.AREA))
            }
        }
    }

    /** Swallows every queued click without acting on it, so re-enabling does not replay a held key. */
    private fun drainClicks() {
        while (brushPaint.consumeClick()) { /* no-op */ }
        while (brushErase.consumeClick()) { /* no-op */ }
        while (cornerFirst.consumeClick()) { /* no-op */ }
        while (cornerSecond.consumeClick()) { /* no-op */ }
        while (clearArea.consumeClick()) { /* no-op */ }
        while (toggleOverlay.consumeClick()) { /* no-op */ }
        while (undo.consumeClick()) { /* no-op */ }
        while (redo.consumeClick()) { /* no-op */ }
        while (openMenu.consumeClick()) { /* no-op */ }
        while (openArea.consumeClick()) { /* no-op */ }
    }

    private fun flipOverlay(client: Minecraft) {
        if (client.level == null) return

        ApSettings.showPaintedOverlay = !ApSettings.showPaintedOverlay
        ApSettings.save()
        PaintedOverlay.invalidate()
        tell(
            client,
            Component.translatable(
                if (ApSettings.showPaintedOverlay) {
                    "austrianpainter.overlay.on"
                } else {
                    "austrianpainter.overlay.off"
                },
            ),
        )
    }

    /** Silent when there is nothing selected, so a stray press does not spam the chat. */
    private fun clearSelection(client: Minecraft) {
        if (client.level == null) return
        if (PaintArea.corner1 == null && PaintArea.corner2 == null &&
            PaintArea.selected.isEmpty() && PaintArea.rules.isEmpty()
        ) {
            return
        }

        PaintArea.clearSelection()
        tell(client, Component.translatable("austrianpainter.area.selection_cleared"))
    }

    private fun markCorner(client: Minecraft, first: Boolean) {
        val pos = PaintSelection.lookedAtPos() ?: run {
            tell(client, Component.translatable("austrianpainter.brush.miss"))
            return
        }

        PaintArea.setCorner(first, pos)
        tell(
            client,
            Component.translatable(
                if (first) "austrianpainter.area.corner_first" else "austrianpainter.area.corner_second",
                pos.x, pos.y, pos.z,
            ),
        )
        if (PaintArea.complete) {
            tell(client, Component.translatable("austrianpainter.area.volume", PaintArea.volume()))
        }
    }

    private fun stroke(client: Minecraft, paint: Boolean) {
        val level = client.level ?: return

        if (!PaintBrush.enabled) {
            tell(
                client,
                Component.translatable("austrianpainter.brush.disabled", KeyHints.name(openMenuKey)),
            )
            return
        }

        val center = PaintSelection.lookedAtPos() ?: run {
            tell(client, Component.translatable("austrianpainter.brush.miss"))
            return
        }

        // Air has no model to repaint, so leave it out of the stroke entirely.
        val positions = PaintBrush.cubeAround(center).filterNot { level.getBlockState(it).isAir }
        if (positions.isEmpty()) return

        if (paint) {
            val donor = PaintBrush.donor ?: run {
                tell(client, Component.translatable("austrianpainter.brush.no_donor"))
                return
            }
            val count = PaintStorage.paintPositions(positions, donor)
            tell(client, Component.translatable("austrianpainter.brush.painted", count, donor.name))
        } else {
            val count = PaintStorage.unpaintPositions(positions)
            tell(client, Component.translatable("austrianpainter.brush.erased", count))
        }
    }

    private fun tell(client: Minecraft, message: Component) {
        client.player?.sendSystemMessage(message)
    }
}
