package com.maxisch.paint

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.resources.ResourceKey
import net.minecraft.util.RandomSource
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
}

/**
 * Positional paint keyed by a room-scope key rather than a dimension - used for both dungeon-room
 * presets (keyed by room name) and boss-room presets (keyed `B<floor>`), which are structurally
 * identical and differ only in which folder and key domain they live in. Coordinates for dungeon
 * rooms are relative to the room's marker corner with its rotation taken out, because a Catacombs
 * room is placed somewhere else every run; see `com.maxisch.dungeon.RoomTransform`. Coordinates for
 * boss rooms are plain world coordinates, since a boss room sits at fixed coordinates every run.
 */
class RoomBlockPreset(
    val positions: MutableMap<String, Long2ObjectOpenHashMap<Block>> = LinkedHashMap(),
) {
    fun forKey(key: String): Long2ObjectOpenHashMap<Block> =
        positions.getOrPut(key) { Long2ObjectOpenHashMap() }

    fun positionsFor(key: String): Long2ObjectOpenHashMap<Block>? = positions[key]

    val size: Int
        get() = positions.values.sumOf { it.size }

    fun isEmpty(): Boolean = positions.values.all { it.isEmpty() }
}

/** Whole-block-type paint: every block of the key type renders as the value type, everywhere. */
class TypePreset(
    val map: MutableMap<Block, Block> = LinkedHashMap(),
) {
    val size: Int
        get() = map.size

    fun isEmpty(): Boolean = map.isEmpty()
}

/**
 * A weighted bag of donor blocks. Only an authoring tool: an area apply draws from it once and
 * writes the concrete result into a [BlockPreset], so nothing here is consulted while rendering.
 */
class PalettePreset(
    val weights: MutableMap<Block, Int> = LinkedHashMap(),
) {
    companion object {
        const val MIN_WEIGHT = 1
        const val MAX_WEIGHT = 100
    }

    val size: Int
        get() = weights.size

    fun isEmpty(): Boolean = weights.isEmpty()

    fun totalWeight(): Int = weights.values.sum()

    /** Cumulative weights, built once per apply rather than per position. Null while empty. */
    fun picker(): Picker? {
        if (weights.isEmpty()) return null

        val blocks = arrayOfNulls<Block>(weights.size)
        val cumulative = IntArray(weights.size)
        var running = 0
        for ((index, entry) in weights.entries.withIndex()) {
            blocks[index] = entry.key
            running += entry.value.coerceIn(MIN_WEIGHT, MAX_WEIGHT)
            cumulative[index] = running
        }
        if (running <= 0) return null

        @Suppress("UNCHECKED_CAST")
        return Picker(blocks as Array<Block>, cumulative)
    }

    class Picker(private val blocks: Array<Block>, private val cumulative: IntArray) {
        private val total = cumulative.last()

        fun next(random: RandomSource): Block {
            val roll = random.nextInt(total)
            var low = 0
            var high = cumulative.size - 1
            while (low < high) {
                val mid = (low + high) / 2
                if (roll < cumulative[mid]) high = mid else low = mid + 1
            }
            return blocks[low]
        }
    }
}
