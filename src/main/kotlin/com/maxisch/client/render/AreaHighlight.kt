package com.maxisch.client.render

import com.maxisch.client.PaintArea
import com.maxisch.paint.ApSettings
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos

/**
 * Draws the area selection with the vanilla gizmo API - the same path the structure block's bounding
 * box uses. Gizmos added during a tick are drained at the end of it and re-submitted every frame, so
 * emitting once per tick is enough to get a stable box.
 */
object AreaHighlight {

    private const val STROKE_WIDTH = 2.0f

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client -> emit(client) }
    }

    private fun emit(client: Minecraft) {
        if (client.level == null) return
        val box = PaintArea.aabb() ?: return

        client.collectPerTickGizmos().use {
            Gizmos.cuboid(
                box,
                GizmoStyle.strokeAndFill(
                    ApSettings.areaOutlineColor,
                    STROKE_WIDTH,
                    ApSettings.areaFillColor,
                ),
            )
        }
    }
}
