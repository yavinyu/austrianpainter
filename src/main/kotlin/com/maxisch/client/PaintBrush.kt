package com.maxisch.client

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block

/**
 * Armed brush state. Pick a donor once, then paint blocks in the world without going back to the
 * menu. Purely client-side and not persisted - the rules it creates are.
 */
object PaintBrush {

    const val MIN_RADIUS = 1
    const val MAX_RADIUS = 5

    var enabled: Boolean = false
    var donor: Block? = null
    var paintSound: Boolean = true

    var radius: Int = MIN_RADIUS
        set(value) {
            field = value.coerceIn(MIN_RADIUS, MAX_RADIUS)
        }

    val armed: Boolean
        get() = enabled && donor != null

    @JvmStatic
    fun adjustRadius(delta: Int): Int {
        radius += delta
        return radius
    }

    /** Cube of side `2 * radius - 1` centred on [center]; radius 1 is the single block. */
    fun cubeAround(center: BlockPos): List<BlockPos> {
        val reach = radius - 1
        if (reach == 0) return listOf(center.immutable())

        val positions = ArrayList<BlockPos>((2 * reach + 1) * (2 * reach + 1) * (2 * reach + 1))
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
