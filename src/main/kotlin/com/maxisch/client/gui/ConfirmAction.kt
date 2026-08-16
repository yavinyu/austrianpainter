package com.maxisch.client.gui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import com.maxisch.client.gui.screen.ApConfirmScreen

/**
 * Gate for destructive one-click buttons (clearing painted blocks) that has no undo path large
 * enough to cover them - see `PaintHistory.MAX_POSITIONS_PER_STEP`. Either choice returns to
 * [current]; [onConfirm] only runs on yes.
 */
object ConfirmAction {
    fun ask(current: Screen, title: Component, message: Component, onConfirm: () -> Unit) {
        Minecraft.getInstance().setScreenAndShow(
            ApConfirmScreen(current, title, message) { confirmed ->
                if (confirmed) onConfirm()
            },
        )
    }
}
