package com.maxisch.dungeon

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.status.ChunkStatus

/**
 * Every world read the room scanner needs, in one place, so the scan itself is only about the grid.
 */
internal object WorldProbe {

    /**
     * The marker Hypixel leaves on one roof corner of every room. Looked up by id rather than
     * through [Blocks] because the dyed-block constants were folded into a colour collection in
     * 26.2, and the id is the one spelling that holds either way.
     */
    val BLUE_TERRACOTTA: Block by lazy {
        BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:blue_terracotta"))
    }

    fun blockAt(pos: BlockPos): Block =
        Minecraft.getInstance().level?.getBlockState(pos)?.block ?: Blocks.AIR

    fun isChunkLoaded(x: Int, z: Int): Boolean {
        val level: Level = Minecraft.getInstance().level ?: return false
        return level.getChunk(x shr 4, z shr 4, ChunkStatus.FULL, false) != null
    }

    /** Roof height at a column; gold blocks are ignored because they are run-specific decoration. */
    fun highestY(x: Int, z: Int): Int {
        val level = Minecraft.getInstance().level ?: return 0
        val cursor = BlockPos.MutableBlockPos(x, 0, z)

        for (y in 256 downTo 0) {
            val state = level.getBlockState(cursor.setY(y))
            if (state.isAir || state.block == Blocks.GOLD_BLOCK) continue
            return y
        }
        return 0
    }

    /**
     * Hash of the block column through a tile centre, which is what identifies a room prefab.
     * Planks and chests are skipped because they are placed per run, not per prefab.
     */
    fun coreHash(x: Int, z: Int): Int {
        val builder = StringBuilder(150)
        val cursor = BlockPos.MutableBlockPos(x, 0, z)
        val level = Minecraft.getInstance().level ?: return 0

        for (y in 140 downTo 12) {
            val id = LegacyBlockIds.idOf(level.getBlockState(cursor.setY(y)))
            if (id == 5 || id == 54 || id == 146) continue
            builder.append(id?.toString() ?: "null")
        }
        return builder.toString().hashCode()
    }
}
