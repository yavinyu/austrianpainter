package com.maxisch.paint

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.maxisch.paint.ApLog.LOGGER
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Which presets a world last used. Both kinds are bound independently. */
data class WorldBinding(var blocks: String, var types: String)

private val BOSS_KEY = Regex("^B\\d+$")

/** `config/ap/settings.json`. */
object ApSettings {

    const val DEFAULT_PRESET = "default"

    const val DEFAULT_AREA_OUTLINE = 0xFFFFAA00.toInt()
    const val DEFAULT_AREA_FILL = 0x30FFAA00

    /** Green for what is already painted, cyan for what an apply is about to paint. */
    const val DEFAULT_PAINTED_OUTLINE = 0xFF55FF55.toInt()
    const val DEFAULT_PREVIEW_OUTLINE = 0xFF55FFFF.toInt()

    const val MIN_OVERLAY_RADIUS = 4
    const val MAX_OVERLAY_RADIUS = 48

    var defaultBlockPreset: String = DEFAULT_PRESET
    var defaultTypePreset: String = DEFAULT_PRESET

    /** Fallback preset used when the current room (if any) has no dungeon-room preset bound to it. */
    var defaultRoomPreset: String = DEFAULT_PRESET

    /** Fallback preset used when the current boss room (if any) has no boss preset bound to it. */
    var defaultBossPreset: String = DEFAULT_PRESET

    /** Fallback palette used when the current room (if any) has no palette bound to it. */
    var activePalette: String = DEFAULT_PRESET

    /** The area ruleset in hand. Global: a ruleset describes a look, not a place. */
    var activeRuleset: String = DEFAULT_PRESET

    /** How many donors the block picker offers back, newest first. One row of its grid. */
    const val MAX_RECENT_DONORS = 9

    /**
     * Block ids of the donors picked most recently, newest first. Kept as ids rather than blocks so
     * a resource pack or mod change cannot fail the settings load; the picker resolves them and
     * silently drops whatever no longer exists.
     */
    private val recentDonors = mutableListOf<String>()

    fun recentDonorIds(): List<String> = recentDonors

    /** Moves [id] to the front, deduped and trimmed. Saves: the list is only touched on a pick. */
    fun rememberDonor(id: String) {
        recentDonors.remove(id)
        recentDonors.add(0, id)
        while (recentDonors.size > MAX_RECENT_DONORS) recentDonors.removeLast()
        save()
    }

    var brushRadius: Int = 1
    var paintSound: Boolean = true
    var showHud: Boolean = true

    /** Adds the brush-resize gesture to the HUD; on until the player turns it off. */
    var showHints: Boolean = true

    /** Whether the one-time "press X to open the paint menu" chat line has already been sent. */
    var seenIntro: Boolean = false

    /** Master switch for the Catacombs room scope; off means paint is always world-absolute. */
    var dungeonRoomScope: Boolean = true

    /** ARGB. The fill is deliberately faint so the box never hides what is inside it. */
    var areaOutlineColor: Int = DEFAULT_AREA_OUTLINE
    var areaFillColor: Int = DEFAULT_AREA_FILL

    /** Outlines painted blocks around the player, since paint is otherwise invisible as paint. */
    var showPaintedOverlay: Boolean = false
    var paintedOverlayRadius: Int = 16
    var paintedOverlayColor: Int = DEFAULT_PAINTED_OUTLINE

    /** Colour of the "this is what Replace would touch" outline in the area tab. */
    var areaPreviewColor: Int = DEFAULT_PREVIEW_OUTLINE

    private val worldPresets = LinkedHashMap<String, WorldBinding>()

    /** Room scope key to block-type preset, swapped in while that room is the active scope. */
    private val roomTypePresets = LinkedHashMap<String, String>()

    /** Dungeon-room name to positional room preset, swapped in while that room is the active scope. */
    private val roomPresets = LinkedHashMap<String, String>()

    /** Boss floor key (`B<floor>`) to positional boss preset, swapped in while it is the active scope. */
    private val bossPresets = LinkedHashMap<String, String>()

    /** Room scope key to palette preset, swapped in while that room is the active scope. */
    private val roomPalettePresets = LinkedHashMap<String, String>()

    fun roomTypePresetFor(scopeKey: String): String? = roomTypePresets[scopeKey]

    fun bindRoomTypes(scopeKey: String, preset: String?) {
        if (preset == null) roomTypePresets.remove(scopeKey) else roomTypePresets[scopeKey] = preset
        save()
    }

    fun roomPresetFor(key: String): String = roomPresets[key] ?: defaultRoomPreset

    fun bindRoomPreset(key: String, preset: String?) {
        if (preset == null) roomPresets.remove(key) else roomPresets[key] = preset
        save()
    }

    fun bossPresetFor(key: String): String = bossPresets[key] ?: defaultBossPreset

    fun bindBossPreset(key: String, preset: String?) {
        if (preset == null) bossPresets.remove(key) else bossPresets[key] = preset
        save()
    }

    fun roomPalettePresetFor(scopeKey: String): String? = roomPalettePresets[scopeKey]

    fun bindRoomPalettes(scopeKey: String, preset: String?) {
        if (preset == null) roomPalettePresets.remove(scopeKey) else roomPalettePresets[scopeKey] = preset
        save()
    }

    fun blockPresetFor(worldKey: String): String =
        worldPresets[worldKey]?.blocks ?: defaultBlockPreset

    fun typePresetFor(worldKey: String): String =
        worldPresets[worldKey]?.types ?: defaultTypePreset

    fun bindBlocks(worldKey: String, preset: String) {
        binding(worldKey).blocks = preset
        save()
    }

    fun bindTypes(worldKey: String, preset: String) {
        binding(worldKey).types = preset
        save()
    }

    private fun binding(worldKey: String): WorldBinding =
        worldPresets.getOrPut(worldKey) { WorldBinding(defaultBlockPreset, defaultTypePreset) }

    /** Called when a preset is renamed, so bindings keep pointing at it. */
    fun renamePreset(kind: PresetKind, from: String, to: String) {
        when (kind) {
            PresetKind.BLOCKS -> {
                for (bound in worldPresets.values) if (bound.blocks == from) bound.blocks = to
                if (defaultBlockPreset == from) defaultBlockPreset = to
            }

            PresetKind.ROOMS -> {
                for (room in roomPresets.keys.toList()) {
                    if (roomPresets[room] == from) roomPresets[room] = to
                }
                if (defaultRoomPreset == from) defaultRoomPreset = to
            }

            PresetKind.BOSSES -> {
                for (room in bossPresets.keys.toList()) {
                    if (bossPresets[room] == from) bossPresets[room] = to
                }
                if (defaultBossPreset == from) defaultBossPreset = to
            }

            PresetKind.TYPES -> {
                for (bound in worldPresets.values) if (bound.types == from) bound.types = to
                for (room in roomTypePresets.keys.toList()) {
                    if (roomTypePresets[room] == from) roomTypePresets[room] = to
                }
                if (defaultTypePreset == from) defaultTypePreset = to
            }

            PresetKind.PALETTES -> {
                for (room in roomPalettePresets.keys.toList()) {
                    if (roomPalettePresets[room] == from) roomPalettePresets[room] = to
                }
                if (activePalette == from) activePalette = to
            }

            PresetKind.RULESETS -> {
                if (activeRuleset == from) activeRuleset = to
            }
        }
        save()
    }

    // ---------------------------------------------------------------- io

    fun load() {
        ApPaths.ensureDirectories()

        val path = ApPaths.settingsFile
        if (path.notExists()) {
            LegacyMigration.run()
            save()
            return
        }

        runCatching {
            val root = JsonParser.parseString(path.readText()).asJsonObject
            defaultBlockPreset = root.get("defaultBlockPreset")?.asString ?: DEFAULT_PRESET
            defaultTypePreset = root.get("defaultTypePreset")?.asString ?: DEFAULT_PRESET
            defaultRoomPreset = root.get("defaultRoomPreset")?.asString ?: DEFAULT_PRESET
            defaultBossPreset = root.get("defaultBossPreset")?.asString ?: DEFAULT_PRESET
            activePalette = root.get("activePalette")?.asString ?: DEFAULT_PRESET
            activeRuleset = root.get("activeRuleset")?.asString ?: DEFAULT_PRESET
            brushRadius = root.get("brushRadius")?.asInt ?: 1
            paintSound = root.get("paintSound")?.asBoolean ?: true
            showHud = root.get("showHud")?.asBoolean ?: true
            showHints = root.get("showHints")?.asBoolean ?: true
            // Defaults false so configs written before the hint existed still get it once.
            seenIntro = root.get("seenIntro")?.asBoolean ?: false
            dungeonRoomScope = root.get("dungeonRoomScope")?.asBoolean ?: true
            areaOutlineColor = root.get("areaOutlineColor")?.asInt ?: DEFAULT_AREA_OUTLINE
            areaFillColor = root.get("areaFillColor")?.asInt ?: DEFAULT_AREA_FILL
            showPaintedOverlay = root.get("showPaintedOverlay")?.asBoolean ?: false
            paintedOverlayRadius = (root.get("paintedOverlayRadius")?.asInt ?: 16)
                .coerceIn(MIN_OVERLAY_RADIUS, MAX_OVERLAY_RADIUS)
            paintedOverlayColor = root.get("paintedOverlayColor")?.asInt ?: DEFAULT_PAINTED_OUTLINE
            areaPreviewColor = root.get("areaPreviewColor")?.asInt ?: DEFAULT_PREVIEW_OUTLINE

            roomTypePresets.clear()
            root.getAsJsonObject("roomTypePresets")?.entrySet()?.forEach { (room, preset) ->
                roomTypePresets[room] = preset.asString
            }

            roomPresets.clear()
            bossPresets.clear()
            root.getAsJsonObject("roomPresets")?.entrySet()?.forEach { (room, preset) ->
                roomPresets[room] = preset.asString
            }
            root.getAsJsonObject("bossPresets")?.entrySet()?.forEach { (room, preset) ->
                bossPresets[room] = preset.asString
            }
            // One-time migration: before rooms and bosses became independent preset kinds, both
            // were bound through one combined map. Split it by key shape so old bindings survive.
            root.getAsJsonObject("roomBlockPresets")?.entrySet()?.forEach { (room, preset) ->
                if (BOSS_KEY.matches(room)) bossPresets.putIfAbsent(room, preset.asString)
                else roomPresets.putIfAbsent(room, preset.asString)
            }

            roomPalettePresets.clear()
            root.getAsJsonObject("roomPalettePresets")?.entrySet()?.forEach { (room, preset) ->
                roomPalettePresets[room] = preset.asString
            }

            recentDonors.clear()
            root.getAsJsonArray("recentDonors")?.forEach { id ->
                if (recentDonors.size < MAX_RECENT_DONORS) recentDonors.add(id.asString)
            }

            worldPresets.clear()
            root.getAsJsonObject("worldPresets")?.entrySet()?.forEach { (world, value) ->
                val bound = value.asJsonObject
                worldPresets[world] = WorldBinding(
                    bound.get("blocks")?.asString ?: defaultBlockPreset,
                    bound.get("types")?.asString ?: defaultTypePreset,
                )
            }
        }.onFailure { LOGGER.error("Could not read {}", path, it) }
    }

    fun save() {
        val root = JsonObject().apply {
            addProperty("defaultBlockPreset", defaultBlockPreset)
            addProperty("defaultTypePreset", defaultTypePreset)
            addProperty("defaultRoomPreset", defaultRoomPreset)
            addProperty("defaultBossPreset", defaultBossPreset)
            addProperty("activePalette", activePalette)
            addProperty("activeRuleset", activeRuleset)
            addProperty("brushRadius", brushRadius)
            addProperty("paintSound", paintSound)
            addProperty("showHud", showHud)
            addProperty("showHints", showHints)
            addProperty("seenIntro", seenIntro)
            addProperty("dungeonRoomScope", dungeonRoomScope)
            addProperty("areaOutlineColor", areaOutlineColor)
            addProperty("areaFillColor", areaFillColor)
            addProperty("showPaintedOverlay", showPaintedOverlay)
            addProperty("paintedOverlayRadius", paintedOverlayRadius)
            addProperty("paintedOverlayColor", paintedOverlayColor)
            addProperty("areaPreviewColor", areaPreviewColor)

            val roomBindings = JsonObject()
            for ((room, preset) in roomTypePresets) roomBindings.addProperty(room, preset)
            add("roomTypePresets", roomBindings)

            val roomPresetBindings = JsonObject()
            for ((room, preset) in roomPresets) roomPresetBindings.addProperty(room, preset)
            add("roomPresets", roomPresetBindings)

            val bossPresetBindings = JsonObject()
            for ((room, preset) in bossPresets) bossPresetBindings.addProperty(room, preset)
            add("bossPresets", bossPresetBindings)

            val roomPaletteBindings = JsonObject()
            for ((room, preset) in roomPalettePresets) roomPaletteBindings.addProperty(room, preset)
            add("roomPalettePresets", roomPaletteBindings)

            val recent = JsonArray()
            for (id in recentDonors) recent.add(id)
            add("recentDonors", recent)

            val bindings = JsonObject()
            for ((world, bound) in worldPresets) {
                bindings.add(
                    world,
                    JsonObject().apply {
                        addProperty("blocks", bound.blocks)
                        addProperty("types", bound.types)
                    },
                )
            }
            add("worldPresets", bindings)
        }

        runCatching {
            ApPaths.ensureDirectories()
            ApPaths.settingsFile.writeText(ApJson.PRETTY.toJson(root) + "\n")
        }.onFailure { LOGGER.error("Could not write {}", ApPaths.settingsFile, it) }
    }

    internal fun bindDuringMigration(worldKey: String, blocks: String?, types: String?) {
        val bound = binding(worldKey)
        blocks?.let { bound.blocks = it }
        types?.let { bound.types = it }
    }
}
