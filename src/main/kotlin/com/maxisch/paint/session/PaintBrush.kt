package com.maxisch.paint.session

import com.maxisch.paint.ApSettings
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block

/**
 * Armed brush state. Pick a donor once, then paint blocks in the world without going back to the
 * menu. Purely client-side; the rules it creates are what gets persisted.
 */
object PaintBrush {

    const val MIN_RADIUS = 1
    const val MAX_RADIUS = 5

    var enabled: Boolean = false
    var donor: Block? = null

    /** Backed by settings so the chosen size survives a restart. */
    var radius: Int
        get() = ApSettings.brushRadius.coerceIn(MIN_RADIUS, MAX_RADIUS)
        set(value) {
            ApSettings.brushRadius = value.coerceIn(MIN_RADIUS, MAX_RADIUS)
        }

    @JvmStatic
    fun adjustRadius(delta: Int): Int {
        radius += delta
        ApSettings.save()
        return radius
    }

    /** Cube of side `2 * radius - 1` centred on [center]; radius 1 is the single block. */
    fun cubeAround(center: BlockPos): List<BlockPos> {
        val reach = radius - 1
        if (reach == 0) return listOf(center.immutable())

        val side = 2 * reach + 1
        val positions = ArrayList<BlockPos>(side * side * side)
        for (x in -reach..reach) {
            for (y in -reach..reach) {
                for (z in -reach..reach) {
                    positions.add(center.offset(x, y, z).immutable())
                }
            }
        }
        return positions
    }
}
