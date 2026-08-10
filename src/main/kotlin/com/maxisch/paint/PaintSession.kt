package com.maxisch.paint

import com.maxisch.dungeon.RoomScope
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
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

        // Rooms and bosses are independent preset kinds, each with its own global default plus a
        // per-key override - unlike blocks/types there is no per-world fallback to chain through.
        val roomChanged = when {
            next == null -> false
            next.isBoss -> {
                val wantedBoss = ApSettings.bossPresetFor(next.key)
                (wantedBoss != PresetStores.bosses.activeName).also { changed ->
                    if (changed) PresetStores.bosses.load(wantedBoss)
                }
            }
            else -> {
                val wantedRoom = ApSettings.roomPresetFor(next.key)
                (wantedRoom != PresetStores.rooms.activeName).also { changed ->
                    if (changed) PresetStores.rooms.load(wantedRoom)
                }
            }
        }

        // Palettes only feed future applies, so swapping one never needs an index or chunk rebuild.
        val wantedPalette = next?.key?.let { ApSettings.roomPalettePresetFor(it) } ?: ApSettings.activePalette
        if (wantedPalette != PresetStores.palettes.activeName) PresetStores.palettes.load(wantedPalette)

        PaintIndexBuilder.refresh()

        // Room borders are crossed constantly; rebuilding every loaded chunk each time would
        // stutter for nothing when neither room is painted and the rules did not change.
        if (presetChanged || roomChanged || hasRoomPaint(previous) || hasRoomPaint(next)) {
            ChunkRebuild.markAll()
        }
    }

    /**
     * The device column or boss zone layer just came on or went off. Both are physically incapable
     * of painting outside their own fixed, compiled-in bounds, so only that footprint ever needs
     * rebuilding - never the whole loaded view.
     *
     * Walking into the arena fires this in the same tick as the boss scope change above, marking
     * the same sections twice in one frame. Cheap enough that suppressing it would cost more state
     * than it saves.
     */
    fun onDeviceScopeChanged() {
        PaintIndexBuilder.refresh()
        val corners = ArrayList<BlockPos>(4)
        DeviceColumns.bounds()?.let { (min, max) -> corners.add(min); corners.add(max) }
        BossZones.bounds().let { (min, max) -> corners.add(min); corners.add(max) }
        ChunkRebuild.markRange(corners)
    }

    private fun hasRoomPaint(room: RoomScope?): Boolean {
        if (room == null) return false
        val store = if (room.isBoss) PresetStores.bosses else PresetStores.rooms
        return store.active.positionsFor(room.key)?.isEmpty() == false
    }

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
        PresetStores.rooms.saveActive()
        PresetStores.bosses.saveActive()
        PresetStores.types.saveActive()
    }

    internal fun markDirty() {
        if (!dirty) dirtySince = System.currentTimeMillis()
        dirty = true
    }

    // ---------------------------------------------------------------- preset switching

    /** World-scoped, so it always binds to the current world regardless of room scope. */
    fun activateBlockPreset(name: String) {
        flush()
        PaintHistory.clear()
        PresetStores.blocks.load(name)
        worldKey?.let { ApSettings.bindBlocks(it, PresetStores.blocks.activeName) }
        PaintIndexBuilder.refresh()
        ChunkRebuild.markAll()
    }

    /**
     * Room-scoped like a palette, not world-scoped like blocks/types: while standing in a normal
     * dungeon room the choice binds to that room; otherwise it becomes the new global default.
     */
    fun activateRoomPreset(name: String) {
        flush()
        PaintHistory.clear()
        PresetStores.rooms.load(name)

        val room = scope?.takeUnless { it.isBoss }?.key
        if (room != null) {
            ApSettings.bindRoomPreset(room, PresetStores.rooms.activeName)
        } else {
            ApSettings.defaultRoomPreset = PresetStores.rooms.activeName
            ApSettings.save()
        }

        PaintIndexBuilder.refresh()
        ChunkRebuild.markAll()
    }

    /** Same shape as [activateRoomPreset], gated on actually standing in a boss room. */
    fun activateBossPreset(name: String) {
        flush()
        PaintHistory.clear()
        PresetStores.bosses.load(name)

        val room = scope?.takeIf { it.isBoss }?.key
        if (room != null) {
            ApSettings.bindBossPreset(room, PresetStores.bosses.activeName)
        } else {
            ApSettings.defaultBossPreset = PresetStores.bosses.activeName
            ApSettings.save()
        }

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
     * no chunk rebuild here. Inside a room the choice binds to that room, like block/type presets.
     */
    fun activatePalette(name: String) {
        PresetStores.palettes.load(name)

        val room = scope?.key
        if (room != null) {
            ApSettings.bindRoomPalettes(room, PresetStores.palettes.activeName)
        } else {
            ApSettings.activePalette = PresetStores.palettes.activeName
            ApSettings.save()
        }
    }

    /**
     * Like a palette, a ruleset only feeds future applies, so nothing already painted changes.
     * Unlike a palette it never binds to a room: a ruleset describes a look, not a place.
     */
    fun activateRuleset(name: String) {
        PresetStores.rulesets.load(name)
        ApSettings.activeRuleset = PresetStores.rulesets.activeName
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
