package com.maxisch.paint

import com.maxisch.dungeon.DungeonLocation
import com.maxisch.dungeon.RoomScanner
import com.maxisch.dungeon.RoomScope
import com.maxisch.dungeon.RoomTransform
import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block

/**
 * Flattens the active presets into [PaintIndex].
 *
 * The index only ever sees absolute world positions, so every room's relative positions are projected
 * here and nothing downstream has to know rooms exist.
 *
 * Every scanned room is projected, not only the one the player is standing in: a room the player has
 * left is still on screen, and the renderer has no notion of "the current room" to fall back on.
 */
internal object PaintIndexBuilder {

    /**
     * World-space projection per room name. A room's stored positions cannot change while the player
     * is not standing in it - [PaintRules.activeSlice] only ever writes to the room in scope - so
     * everything but the active room is cached here and re-projected only when the layout or the
     * preset behind it changes. See [invalidateRooms].
     */
    private val projected = HashMap<String, Long2ObjectOpenHashMap<Block>>()

    /** Drops the projection cache; call whenever a preset load or a rescan can have changed it. */
    fun invalidateRooms() {
        projected.clear()
    }

    /**
     * Drops one room's projection, for the room coming into scope: its entry was made before the
     * player walked in and every edit made inside would leave it behind. Nothing re-caches a room
     * while it is in scope, so walking back out re-projects the slice as it now stands.
     */
    fun invalidateRoom(name: String) {
        projected.remove(name)
    }

    fun refresh() {
        // Cheap: the rules themselves are cached in DeviceColumns and only rebuilt when one of
        // them changes, so a brush stroke does not re-read palette files.
        val columns = DeviceColumns.activeColumns()
        val zones = BossZones.activeZones()
        val dimension = PaintSession.currentDimension()
        val absolute = dimension?.let { PaintRules.blocks.positionsIn(it) }

        val scope = PaintSession.scope
        val rooms = roomProjections(scope)
        val boss = bossPositions(scope)
        val active = activeRoom(scope)

        if (rooms.isEmpty() && boss == null && active == null) {
            PaintIndex.rebuild(absolute, PaintRules.types.map, columns, zones)
            return
        }

        val size = rooms.sumOf { it.size } + (boss?.size ?: 0) + (active?.size ?: 0)
        val merged = if (absolute == null) {
            Long2ObjectOpenHashMap<Block>(size)
        } else {
            Long2ObjectOpenHashMap(absolute)
        }
        // Later layers win: a room's own paint beats the dimension's, and the room the player is
        // standing in beats its own cached projection, which is what makes a brush stroke show up.
        rooms.forEach { merged.putAll(it) }
        boss?.let { merged.putAll(it) }
        active?.let { merged.putAll(it) }
        PaintIndex.rebuild(merged, PaintRules.types.map, columns, zones)
    }

    /** Every oriented room with paint in the active preset, except the one currently in scope. */
    private fun roomProjections(scope: RoomScope?): List<Long2ObjectMap<Block>> {
        if (!ApSettings.dungeonRoomScope) return emptyList()
        if (!DungeonLocation.inDungeon) return emptyList()

        val preset = PresetStores.rooms.active
        val out = ArrayList<Long2ObjectMap<Block>>()
        for (room in RoomScanner.orientedRooms()) {
            if (room.name == scope?.key) continue
            val cached = projected[room.name]
            if (cached != null) {
                if (!cached.isEmpty()) out.add(cached)
                continue
            }
            val roomScope = RoomScanner.scopeFor(room) ?: continue
            val stored = preset.positionsFor(room.name)
            val world = project(stored, roomScope)
            projected[room.name] = world
            if (!world.isEmpty()) out.add(world)
        }
        return out
    }

    /**
     * Boss paint, only while the player is actually in the arena. Unlike a dungeon room a boss room
     * is painted wall to wall, and carrying that many positions in the index for the whole run costs
     * a hash lookup per block on every chunk bake anywhere in the dungeon. The arena is nowhere near
     * the rooms, so there is nothing to see from outside it anyway.
     *
     * Read straight out of the preset: boss coordinates are already absolute world coordinates.
     */
    private fun bossPositions(scope: RoomScope?): Long2ObjectMap<Block>? {
        if (scope == null || !scope.isBoss) return null
        return PresetStores.bosses.active.positionsFor(scope.key)?.takeUnless { it.isEmpty() }
    }

    /**
     * The room in scope, projected fresh rather than from the cache so an edit made this tick is in
     * the index. A boss scope needs nothing here: its paint is absolute and [bossPositions] already
     * has it, whether the player is in the arena or not.
     */
    private fun activeRoom(scope: RoomScope?): Long2ObjectMap<Block>? {
        if (scope == null || scope.isBoss) return null
        val stored = PresetStores.rooms.active.positionsFor(scope.key)?.takeUnless { it.isEmpty() }
            ?: return null
        return project(stored, scope)
    }

    private fun project(stored: Long2ObjectMap<Block>?, scope: RoomScope): Long2ObjectOpenHashMap<Block> {
        if (stored == null || stored.isEmpty()) return Long2ObjectOpenHashMap()
        val world = Long2ObjectOpenHashMap<Block>(stored.size)
        for (entry in stored.long2ObjectEntrySet()) {
            val pos = RoomTransform.toWorld(BlockPos.of(entry.longKey), scope.origin, scope.rotation)
            world.put(pos.asLong(), entry.value)
        }
        return world
    }
}
