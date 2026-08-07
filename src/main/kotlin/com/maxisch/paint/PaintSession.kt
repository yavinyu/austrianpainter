package com.maxisch.paint

import com.maxisch.dungeon.RoomScope
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

/**
 * What the rules currently apply to, and when they get written back.
 *
 * Holds the world identity and the dungeon room in scope, swaps presets when either changes, and
 * owns the debounce. Writes are debounced because a radius-5 brush click adds up to 729 positions
 * and can fire several times a second, so saving on every mutation would rewrite the whole preset
 * file continuously.
 */
object PaintSession {

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

    // ---------------------------------------------------------------- lifecycle

    fun onJoinWorld() {
        val key = resolveWorldKey()
        worldKey = key
        // A server switch drops whatever room was in scope; the tracker rediscovers it.
        scope = null
        PaintHistory.clear()

        PresetStores.blocks.load(ApSettings.blockPresetFor(key))
        PresetStores.types.load(ApSettings.typePresetFor(key))
        PaintIndexBuilder.refresh()
    }

    fun onLeaveWorld() {
        flush()
        worldKey = null
        scope = null
        PaintHistory.clear()
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
        // Recorded coordinates are in the old slice's space, so they cannot be replayed here.
        PaintHistory.clear()

        val wanted = next?.key?.let { ApSettings.roomTypePresetFor(it) }
            ?: worldKey?.let { ApSettings.typePresetFor(it) }
        val presetChanged = wanted != null && wanted != PresetStores.types.activeName
        if (presetChanged) PresetStores.types.load(wanted)

        PaintIndexBuilder.refresh()

        // Room borders are crossed constantly; rebuilding every loaded chunk each time would
        // stutter for nothing when neither room is painted and the rules did not change.
        if (presetChanged || hasRoomPaint(previous) || hasRoomPaint(next)) ChunkRebuild.markAll()
    }

    private fun hasRoomPaint(room: RoomScope?): Boolean =
        room != null && PaintRules.blocks.positionsInRoom(room.key)?.isEmpty() == false

    // ---------------------------------------------------------------- debounced writes

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

    internal fun markDirty() {
        if (!dirty) dirtySince = System.currentTimeMillis()
        dirty = true
    }

    // ---------------------------------------------------------------- preset switching

    fun activateBlockPreset(name: String) {
        flush()
        PaintHistory.clear()
        PresetStores.blocks.load(name)
        worldKey?.let { ApSettings.bindBlocks(it, PresetStores.blocks.activeName) }
        PaintIndexBuilder.refresh()
        ChunkRebuild.markAll()
    }

    /** Inside a room the choice binds to that room, so walking back in restores it. */
    fun activateTypePreset(name: String) {
        flush()
        PaintHistory.clear()
        PresetStores.types.load(name)

        val room = scope?.key
        if (room != null) {
            ApSettings.bindRoomTypes(room, PresetStores.types.activeName)
        } else {
            worldKey?.let { ApSettings.bindTypes(it, PresetStores.types.activeName) }
        }

        PaintIndexBuilder.refresh()
        ChunkRebuild.markAll()
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

    // ---------------------------------------------------------------- world identity

    internal fun currentDimension(): ResourceKey<Level>? = Minecraft.getInstance().level?.dimension()

    private fun resolveWorldKey(): String {
        val mc = Minecraft.getInstance()
        val raw = mc.singleplayerServer?.worldData?.levelName
            ?: mc.currentServer?.ip
            ?: "unknown"
        return ApPaths.sanitize(raw).ifEmpty { "unknown" }
    }
}
