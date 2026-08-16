package com.maxisch.client.render.overlay

import com.maxisch.paint.settings.ApSettings
import com.maxisch.paint.PaintIndex
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos

/**
 * Shows which blocks around the player are painted.
 *
 * Paint is invisible by design - a painted block renders as its donor - so without this there is no
 * way to tell your own work from the world, or to find a stray position you painted by accident.
 *
 * The set is recomputed on a throttle rather than per frame: the walk is a cube of side
 * `2 * radius + 1` and nothing about it changes between ticks unless the player moves or the rules
 * do. [BlockHighlight] owns the cap and the drawing.
 */
object PaintedOverlay {

    /** Ticks between recomputes while the player stands still. */
    private const val REFRESH_TICKS = 10

    private var ticks = 0
    private var lastCenter: BlockPos? = null

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client -> tick(client) }
    }

    /** Recomputes now, so toggling on or painting something shows up without waiting a refresh. */
    fun invalidate() {
        ticks = REFRESH_TICKS
        lastCenter = null
    }

    private fun tick(client: Minecraft) {
        if (!ApSettings.showPaintedOverlay) {
            if (lastCenter != null) {
                lastCenter = null
                BlockHighlight.clearPainted()
            }
            return
        }

        val level = client.level
        val player = client.player
        if (level == null || player == null) {
            BlockHighlight.clearPainted()
            return
        }

        val center = player.blockPosition()
        ticks++
        if (ticks < REFRESH_TICKS && center == lastCenter) return
        ticks = 0
        lastCenter = center

        val radius = ApSettings.paintedOverlayRadius
        val hits = ArrayList<BlockPos>()
        val cursor = BlockPos.MutableBlockPos()

        for (x in center.x - radius..center.x + radius) {
            for (y in center.y - radius..center.y + radius) {
                for (z in center.z - radius..center.z + radius) {
                    cursor.set(x, y, z)
                    val state = level.getBlockState(cursor)
                    if (state.isAir) continue
                    if (PaintIndex.paintAt(cursor, state) == null) continue
                    hits.add(cursor.immutable())
                    // No early exit at the cap: BlockHighlight needs the true size to say whether
                    // it refused, and the walk is bounded by the radius anyway.
                }
            }
        }

        BlockHighlight.showPainted(hits, ApSettings.paintedOverlayColor)
    }
}
