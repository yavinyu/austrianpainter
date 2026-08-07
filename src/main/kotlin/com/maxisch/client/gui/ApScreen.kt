package com.maxisch.client.gui

import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Shared chrome for the mod's own screens: close returns to whoever opened you, and the world keeps
 * ticking behind the menu so painting can be judged against a moving scene.
 */
abstract class ApScreen(title: Component, protected val parent: Screen?) : Screen(title) {

    override fun onClose() {
        val target = parent
        if (target == null) super.onClose() else minecraft.setScreenAndShow(target)
    }

    override fun isPauseScreen(): Boolean = false
}
