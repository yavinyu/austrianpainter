package com.maxisch.client.gui.screen

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Block

/**
 * The one block-search predicate. Both the picker grid and the area list search the same way, so a
 * query that finds a block in one finds it in the other.
 */
object BlockSearch {

    /** Normalises a typed query once, so the per-block test stays a plain `contains`. */
    fun normalize(query: String): String = query.trim().lowercase()

    /** [query] must already be [normalize]d. A blank query matches everything. */
    fun matches(block: Block, query: String): Boolean {
        if (query.isEmpty()) return true
        return BuiltInRegistries.BLOCK.getKey(block).toString().contains(query) ||
            block.name.string.lowercase().contains(query)
    }
}
