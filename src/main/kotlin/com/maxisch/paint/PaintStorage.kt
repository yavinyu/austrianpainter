package com.maxisch.paint

import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block

/**
 * Owns the live rule set: whichever block preset and block-type preset the current world is bound
 * to, plus the wiring that pushes them into [PaintIndex] and asks the renderer to rebuild.
 *
 * Writes are debounced. A radius-5 brush click adds up to 729 positions and can fire several times
 * a second, so saving on every mutation would rewrite the whole preset file continuously.
 */
object PaintStorage {

    private const val FLUSH_DELAY_MS = 2_000L

    /** Identifies the world/server the bindings belong to; null while not in a world. */
    var worldKey: String? = null
        private set

    private var dirty = false
    private var dirtySince = 0L

    private val blocks: BlockPreset
        get() = PresetStores.blocks.active

    private val types: TypePreset
        get() = PresetStores.types.active

    // ---------------------------------------------------------------- lifecycle

    fun onJoinWorld() {
        val key = resolveWorldKey()
        worldKey = key

        PresetStores.blocks.load(ApSettings.blockPresetFor(key))
        PresetStores.types.load(ApSettings.typePresetFor(key))
        refreshIndex()
    }

    fun onLeaveWorld() {
        flush()
        worldKey = null
        PaintIndex.clear()
    }

    /** Called every client tick; the actual write only happens once the burst has settled. */
    fun tick() {
        if (!dirty) return
        if (System.currentTimeMillis() - dirtySince < FLUSH_DELAY_MS) return
        flush()
    }

    fun flush() {
        if (!dirty) return
        dirty = false
        PresetStores.blocks.saveActive()
        PresetStores.types.saveActive()
    }

    private fun markDirty() {
        if (!dirty) dirtySince = System.currentTimeMillis()
        dirty = true
    }

    /** Rebuilds the index for the dimension the player is currently in. */
    fun refreshIndex() {
        val dimension = currentDimension()
        val positions = dimension?.let { blocks.positionsIn(it) }
        PaintIndex.rebuild(positions, types.map)
    }

    fun currentDimension(): ResourceKey<Level>? = Minecraft.getInstance().level?.dimension()

    // ---------------------------------------------------------------- preset switching

    fun activateBlockPreset(name: String) {
        flush()
        PresetStores.blocks.load(name)
        worldKey?.let { ApSettings.bindBlocks(it, PresetStores.blocks.activeName) }
        refreshIndex()
        markEverythingDirty()
    }

    fun activateTypePreset(name: String) {
        flush()
        PresetStores.types.load(name)
        worldKey?.let { ApSettings.bindTypes(it, PresetStores.types.activeName) }
        refreshIndex()
        markEverythingDirty()
    }

    // ---------------------------------------------------------------- type rules

    fun typeRules(): Map<Block, Block> = types.map

    fun setTypeRule(source: Block, target: Block) {
        types.map[source] = target
        refreshIndex()
        PresetStores.types.saveActive()
        markEverythingDirty()
    }

    fun removeTypeRule(source: Block) {
        if (types.map.remove(source) == null) return
        refreshIndex()
        PresetStores.types.saveActive()
        markEverythingDirty()
    }

    // ---------------------------------------------------------------- positional rules

    /** Donor block for every painted position in this dimension, grouped for the rule list. */
    fun positionsByDonor(): Map<Block, Int> {
        val dimension = currentDimension() ?: return emptyMap()
        val positions = blocks.positionsIn(dimension) ?: return emptyMap()

        val counts = LinkedHashMap<Block, Int>()
        for (entry in positions.long2ObjectEntrySet()) {
            counts.merge(entry.value, 1, Int::plus)
        }
        return counts
    }

    fun paintPositions(positions: Collection<BlockPos>, target: Block): Int {
        if (positions.isEmpty()) return 0
        val dimension = currentDimension() ?: return 0

        val map = blocks.forDimension(dimension)
        positions.forEach { map.put(it.asLong(), target) }

        refreshIndex()
        markDirty()
        markRangeDirty(positions)
        return positions.size
    }

    fun unpaintPositions(positions: Collection<BlockPos>): Int {
        if (positions.isEmpty()) return 0
        val dimension = currentDimension() ?: return 0
        val map = blocks.positionsIn(dimension) ?: return 0

        var removed = 0
        for (pos in positions) {
            if (map.remove(pos.asLong()) != null) removed++
        }
        if (removed == 0) return 0

        refreshIndex()
        markDirty()
        markRangeDirty(positions)
        return removed
    }

    /** Clears every position painted with [target] in this dimension. */
    fun removeDonor(target: Block): Int {
        val dimension = currentDimension() ?: return 0
        val map = blocks.positionsIn(dimension) ?: return 0

        val doomed = LongOpenHashSet()
        for (entry in map.long2ObjectEntrySet()) {
            if (entry.value == target) doomed.add(entry.longKey)
        }
        if (doomed.isEmpty()) return 0

        doomed.forEach { map.remove(it) }
        refreshIndex()
        markDirty()
        markEverythingDirty()
        return doomed.size
    }

    fun clearCurrentDimension() {
        val dimension = currentDimension() ?: return
        val map = blocks.positionsIn(dimension) ?: return
        if (map.isEmpty()) return

        map.clear()
        refreshIndex()
        markDirty()
        markEverythingDirty()
    }

    // ---------------------------------------------------------------- re-render

    private fun markRangeDirty(positions: Collection<BlockPos>) {
        val level = Minecraft.getInstance().level ?: return
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var maxZ = Int.MIN_VALUE
        for (pos in positions) {
            if (pos.x < minX) minX = pos.x
            if (pos.y < minY) minY = pos.y
            if (pos.z < minZ) minZ = pos.z
            if (pos.x > maxX) maxX = pos.x
            if (pos.y > maxY) maxY = pos.y
            if (pos.z > maxZ) maxZ = pos.z
        }
        level.setSectionRangeDirty(
            SectionPos.blockToSectionCoord(minX) - 1,
            SectionPos.blockToSectionCoord(minY) - 1,
            SectionPos.blockToSectionCoord(minZ) - 1,
            SectionPos.blockToSectionCoord(maxX) + 1,
            SectionPos.blockToSectionCoord(maxY) + 1,
            SectionPos.blockToSectionCoord(maxZ) + 1,
        )
    }

    /** Type rules and preset swaps can hit anything, so rebuild the whole loaded view. */
    private fun markEverythingDirty() {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return
        val radius = mc.options.renderDistance().get() + 1
        val section = SectionPos.of(player.blockPosition())
        level.setSectionRangeDirty(
            section.x - radius, level.minSectionY, section.z - radius,
            section.x + radius, level.maxSectionY, section.z + radius,
        )
    }

    // ---------------------------------------------------------------- world identity

    private fun resolveWorldKey(): String {
        val mc = Minecraft.getInstance()
        val raw = mc.singleplayerServer?.worldData?.levelName
            ?: mc.currentServer?.ip
            ?: "unknown"
        return ApPaths.sanitize(raw).ifEmpty { "unknown" }
    }
}
