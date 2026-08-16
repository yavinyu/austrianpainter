package com.maxisch.paint

import com.maxisch.dungeon.RoomScanner
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
        // Unconditionally, even though a room may swap it again later: every scanned room is
        // projected into the index from the moment it orients, so the preset behind them has to be
        // in memory before the player walks anywhere. Bosses are the opposite - see [onScopeChanged].
        PresetStores.rooms.load(ApSettings.defaultRoomPreset)
        PaintIndexBuilder.invalidateRooms()
        PaintIndexBuilder.refresh()
    }

    fun onLeaveWorld() {
        flush()
        worldKey = null
        scope = null
        PaintHistory.clear()
        PaintIndexBuilder.invalidateRooms()
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
                // Walking into the arena is the only thing that reads the boss preset off disk, so
                // the store can still be untouched here with the wanted name already on it - the
                // name comparison alone would skip the one load that matters.
                val wantedBoss = ApSettings.bossPresetFor(next.key)
                val store = PresetStores.bosses
                (!store.isLoaded || wantedBoss != store.activeName).also { changed ->
                    if (changed) {
                        store.load(wantedBoss)
                        // P1/P2/P3's rules are keyed by the active boss preset's name - a different
                        // one just loaded, so the cached layers built from the old one are stale.
                        DeviceColumns.invalidate()
                        BossZones.invalidate()
                    }
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

        if (presetChanged || roomChanged) PaintIndexBuilder.invalidateRooms()
        // The room being entered is the only one whose paint can change from here, and its cached
        // projection was taken before the player walked in.
        next?.takeUnless { it.isBoss }?.let { PaintIndexBuilder.invalidateRoom(it.key) }
        PaintIndexBuilder.refresh()

        // Every dungeon room's paint is in the index the whole time now, so crossing a border
        // changes nothing on screen by itself. A preset swap repaints every room at once, and the
        // boss layer is the one thing that still comes and goes with the scope - both need the view
        // re-baked, and walking into the arena happens once a run.
        val bossChanged = previous?.isBoss == true || next?.isBoss == true
        if (presetChanged || roomChanged || bossChanged) ChunkRebuild.markAll()
    }

    /**
     * The dungeon floor was just identified, or a new run started. The room preset is otherwise only
     * loaded on entering a room, and that layer now renders from outside the room it belongs to, so
     * it has to be in memory before the player walks anywhere. The boss preset stays on disk until
     * the arena is entered; nothing draws it before then.
     */
    fun onDungeonFloorChanged(floor: Int?) {
        if (floor == null) return onRoomVisibilityChanged()

        flush()
        PaintIndexBuilder.invalidateRooms()
        // While a room is in scope onScopeChanged owns which room preset is loaded; overriding it
        // with the global default here would drop that room's own binding.
        if (scope == null) {
            val wantedRoom = ApSettings.defaultRoomPreset
            if (wantedRoom != PresetStores.rooms.activeName) PresetStores.rooms.load(wantedRoom)
        }

        PaintIndexBuilder.refresh()
        ChunkRebuild.markAll()
    }

    /**
     * Every room's paint just appeared or disappeared at once - the room-scope setting was flipped,
     * or the run ended - so the whole loaded view has to be re-baked.
     */
    fun onRoomVisibilityChanged() {
        PaintIndexBuilder.invalidateRooms()
        PaintIndexBuilder.refresh()
        ChunkRebuild.markAll()
    }

    /**
     * A room finished scanning and knows where it sits. Only that room's own footprint can have
     * changed on screen, and rooms orient in bursts as chunks load, so rebuilding the whole view
     * per room would stutter through the first walk of every dungeon.
     */
    fun onRoomLayoutChanged() {
        PaintIndexBuilder.invalidateRooms()
        PaintIndexBuilder.refresh()

        val floor = Minecraft.getInstance().level?.minY ?: return
        val preset = PresetStores.rooms.active
        for (room in RoomScanner.drainNewlyOriented()) {
            if (preset.positionsFor(room.name)?.isEmpty() != false) continue
            val (min, max) = room.footprint(floor) ?: continue
            ChunkRebuild.markRange(listOf(min, max))
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

        PaintIndexBuilder.invalidateRooms()
        PaintIndexBuilder.refresh()
        ChunkRebuild.markAll()
    }

    /** Same shape as [activateRoomPreset], gated on actually standing in a boss room. */
    fun activateBossPreset(name: String) {
        flush()
        PaintHistory.clear()
        PresetStores.bosses.load(name)
        // P1/P2/P3's rules are keyed by the active boss preset's name - see the same call in
        // onScopeChanged above.
        DeviceColumns.invalidate()
        BossZones.invalidate()

        val room = scope?.takeIf { it.isBoss }?.key
        if (room != null) {
            ApSettings.bindBossPreset(room, PresetStores.bosses.activeName)
        } else {
            ApSettings.defaultBossPreset = PresetStores.bosses.activeName
            ApSettings.save()
        }

        PaintIndexBuilder.invalidateRooms()
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
