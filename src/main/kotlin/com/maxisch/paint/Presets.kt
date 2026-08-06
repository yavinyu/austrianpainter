package com.maxisch.paint

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block

/**
 * Positional paint: which donor block each coordinate borrows its look from, per dimension.
 * Coordinates are packed with [net.minecraft.core.BlockPos.asLong].
 */
class BlockPreset(
    val dimensions: MutableMap<ResourceKey<Level>, Long2ObjectOpenHashMap<Block>> = LinkedHashMap(),
) {
    fun forDimension(dimension: ResourceKey<Level>): Long2ObjectOpenHashMap<Block> =
        dimensions.getOrPut(dimension) { Long2ObjectOpenHashMap() }

    fun positionsIn(dimension: ResourceKey<Level>): Long2ObjectOpenHashMap<Block>? =
        dimensions[dimension]

    val size: Int
        get() = dimensions.values.sumOf { it.size }

    fun isEmpty(): Boolean = dimensions.values.all { it.isEmpty() }

    fun copy(): BlockPreset {
        val copy = BlockPreset()
        for ((dimension, positions) in dimensions) {
            copy.dimensions[dimension] = Long2ObjectOpenHashMap(positions)
        }
        return copy
    }
}

/** Whole-block-type paint: every block of the key type renders as the value type, everywhere. */
class TypePreset(
    val map: MutableMap<Block, Block> = LinkedHashMap(),
) {
    val size: Int
        get() = map.size

    fun isEmpty(): Boolean = map.isEmpty()

    fun copy(): TypePreset = TypePreset(LinkedHashMap(map))
}
