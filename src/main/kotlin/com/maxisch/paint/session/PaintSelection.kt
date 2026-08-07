package com.maxisch.paint.session

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

/** Transient, client-only selection state shared between the keybinds and the paint screens. */
object PaintSelection {

    enum class Mode { TYPE, POSITION }

    var mode: Mode = Mode.POSITION
    var target: Block? = null

    /** Block position under the crosshair, or null if the player is not looking at a block. */
    fun lookedAtPos(): BlockPos? {
        val hit = Minecraft.getInstance().hitResult
        if (hit == null || hit.type != HitResult.Type.BLOCK) return null
        return (hit as BlockHitResult).blockPos
    }

    fun lookedAtBlock(): Block? {
        val pos = lookedAtPos() ?: return null
        return Minecraft.getInstance().level?.getBlockState(pos)?.block
    }

    fun reset() {
        target = null
    }
}
