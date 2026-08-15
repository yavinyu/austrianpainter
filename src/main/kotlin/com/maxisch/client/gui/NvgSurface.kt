package com.maxisch.client.gui

import com.maxisch.client.render.render2d.NVGUtils
import com.maxisch.client.render.render2d.NvgBridge
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle

/**
 * Runs [nvg] as a NanoVG layer covering the given rect, or [vanilla] if NanoVG is unavailable.
 *
 * The fallback is not defensive padding: NanoVG needs a native library extracted at runtime, and
 * that can fail on a locked-down temp directory, an antivirus quarantine or a platform whose
 * classifier is missing. Every widget here already had a working vanilla implementation before this
 * port, so keeping it costs a few lines and turns a hard failure into a menu that merely looks like
 * the previous release.
 *
 * Coordinates inside [nvg] are absolute Minecraft GUI pixels - the bridge applies the scale and
 * origin translate - so a widget passes its own `x`/`y` straight to the draw calls.
 */
inline fun nvgSurface(
    graphics: GuiGraphicsExtractor,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    clip: ScreenRectangle? = null,
    // crossinline: the bridge defers this into a Runnable, so it cannot carry a non-local return.
    crossinline nvg: () -> Unit,
    vanilla: () -> Unit,
) {
    if (NVGUtils.isDisabled()) {
        vanilla()
    } else {
        NvgBridge.draw(graphics, x, y, width, height, clip) { nvg() }
    }
}
