package com.maxisch.paint

import com.maxisch.dungeon.RoomScope
import com.maxisch.dungeon.RoomTransform
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
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

    /**
     * The dungeon room the player is standing in, if any. While it is set every edit goes to that
     * room's slice in room-relative coordinates instead of to the dimension's absolute one.
     */
    var scope: RoomScope? = null
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
        // A server switch drops whatever room was in scope; the tracker rediscovers it.
        scope = null

        PresetStores.blocks.load(ApSettings.blockPresetFor(key))
        PresetStores.types.load(ApSettings.typePresetFor(key))
        refreshIndex()
    }

    fun onLeaveWorld() {
        flush()
        worldKey = null
        scope = null
        PaintIndex.clear()
    }

    /**
     * Called when the player walks into or out of a dungeon room. The room can carry its own
     * block-type preset, so this swaps presets as well as re-projecting positions.
     */
    fun onScopeChanged(next: RoomScope?) {
        flush()

        val previous = scope
        scope = next

        val wanted = next?.key?.let { ApSettings.roomTypePresetFor(it) }
            ?: worldKey?.let { ApSettings.typePresetFor(it) }
        val presetChanged = wanted != null && wanted != PresetStores.types.activeName
        if (presetChanged) PresetStores.types.load(wanted)

        refreshIndex()

        // Room borders are crossed constantly; rebuilding every loaded chunk each time would
        // stutter for nothing when neither room is painted and the rules did not change.
        if (presetChanged || hasRoomPaint(previous) || hasRoomPaint(next)) markEverythingDirty()
    }

    private fun hasRoomPaint(room: RoomScope?): Boolean =
        room != null && blocks.positionsInRoom(room.key)?.isEmpty() == false

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

    /**
     * Rebuilds the index for the dimension the player is currently in, with the active room's
     * positions projected onto their world coordinates on top. The index itself only ever sees
     * absolute positions, so nothing downstream has to know rooms exist.
     */
    fun refreshIndex() {
        val dimension = currentDimension()
        val absolute = dimension?.let { blocks.positionsIn(it) }
        val room = scope?.let { blocks.positionsInRoom(it.key) }?.takeUnless { it.isEmpty() }

        if (room == null) {
            PaintIndex.rebuild(absolute, types.map)
            return
        }

        val active = scope!!
        val merged = if (absolute == null) {
            Long2ObjectOpenHashMap<Block>(room.size)
        } else {
            Long2ObjectOpenHashMap(absolute)
        }
        for (entry in room.long2ObjectEntrySet()) {
            val world = RoomTransform.toWorld(BlockPos.of(entry.longKey), active.origin, active.rotation)
            merged.put(world.asLong(), entry.value)
        }
        PaintIndex.rebuild(merged, types.map)
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

    /** Inside a room the choice binds to that room, so walking back in restores it. */
    fun activateTypePreset(name: String) {
        flush()
        PresetStores.types.load(name)

        val room = scope?.key
        if (room != null) {
            ApSettings.bindRoomTypes(room, PresetStores.types.activeName)
        } else {
            worldKey?.let { ApSettings.bindTypes(it, PresetStores.types.activeName) }
        }

        refreshIndex()
        markEverythingDirty()
    }

    /**
     * Palettes only feed future applies, so nothing already painted changes - no index rebuild and
     * no chunk rebuild here.
     */
    fun activatePalette(name: String) {
        PresetStores.palettes.load(name)
        ApSettings.activePalette = PresetStores.palettes.activeName
        ApSettings.save()
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

    /**
     * Where positional edits go: the active room's slice while one is in scope, the dimension's
     * otherwise. [toStorage] maps a world position into whatever space that slice is stored in.
     */
    private class Slice(
        val map: Long2ObjectOpenHashMap<Block>,
        val toStorage: (BlockPos) -> BlockPos,
    )

    private fun activeSlice(create: Boolean): Slice? {
        val room = scope
        if (room != null) {
            val map = if (create) blocks.forRoom(room.key) else blocks.positionsInRoom(room.key)
            return Slice(map ?: return null) { RoomTransform.toRelative(it, room.origin, room.rotation) }
        }

        val dimension = currentDimension() ?: return null
        val map = if (create) blocks.forDimension(dimension) else blocks.positionsIn(dimension)
        return Slice(map ?: return null) { it }
    }

    /** Donor block for every painted position in the active scope, grouped for the rule list. */
    fun positionsByDonor(): Map<Block, Int> {
        val slice = activeSlice(create = false) ?: return emptyMap()

        val counts = LinkedHashMap<Block, Int>()
        for (entry in slice.map.long2ObjectEntrySet()) {
            counts.merge(entry.value, 1, Int::plus)
        }
        return counts
    }

    fun paintPositions(positions: Collection<BlockPos>, target: Block): Int =
        paintPositions(positions) { target }

    /**
     * [donorFor] picks the donor per position, which is how a palette apply spreads a weighted mix
     * over the area. A lambda rather than a prepared map: an area can hold millions of positions.
     */
    fun paintPositions(positions: Collection<BlockPos>, donorFor: (BlockPos) -> Block): Int {
        if (positions.isEmpty()) return 0
        val slice = activeSlice(create = true) ?: return 0

        // The donor is picked from the world position so a palette apply keeps its spread even
        // though the position is filed under the room's own coordinates.
        positions.forEach { slice.map.put(slice.toStorage(it).asLong(), donorFor(it)) }

        refreshIndex()
        markDirty()
        markRangeDirty(positions)
        return positions.size
    }

    fun unpaintPositions(positions: Collection<BlockPos>): Int {
        if (positions.isEmpty()) return 0
        val slice = activeSlice(create = false) ?: return 0

        var removed = 0
        for (pos in positions) {
            if (slice.map.remove(slice.toStorage(pos).asLong()) != null) removed++
        }
        if (removed == 0) return 0

        refreshIndex()
        markDirty()
        markRangeDirty(positions)
        return removed
    }

    /** Clears every position painted with [target] in the active scope. */
    fun removeDonor(target: Block): Int {
        val slice = activeSlice(create = false) ?: return 0
        val map = slice.map

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

    /** Clears everything painted in the active scope - the current room, or the dimension. */
    fun clearCurrentScope() {
        val map = activeSlice(create = false)?.map ?: return
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
