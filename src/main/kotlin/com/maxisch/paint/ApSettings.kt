package com.maxisch.paint

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.maxisch.paint.ApLog.LOGGER
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Which presets a world last used. Both kinds are bound independently. */
data class WorldBinding(var blocks: String, var types: String)

/** `config/ap/settings.json`. */
object ApSettings {

    const val DEFAULT_PRESET = "default"

    const val DEFAULT_AREA_OUTLINE = 0xFFFFAA00.toInt()
    const val DEFAULT_AREA_FILL = 0x30FFAA00

    var defaultBlockPreset: String = DEFAULT_PRESET
    var defaultTypePreset: String = DEFAULT_PRESET

    /** Fallback palette used when the current room (if any) has no palette bound to it. */
    var activePalette: String = DEFAULT_PRESET
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

    private val worldPresets = LinkedHashMap<String, WorldBinding>()

    /** Room scope key to block-type preset, swapped in while that room is the active scope. */
    private val roomTypePresets = LinkedHashMap<String, String>()

    /** Room scope key to positional block preset, swapped in while that room is the active scope. */
    private val roomBlockPresets = LinkedHashMap<String, String>()

    /** Room scope key to palette preset, swapped in while that room is the active scope. */
    private val roomPalettePresets = LinkedHashMap<String, String>()

    fun roomTypePresetFor(scopeKey: String): String? = roomTypePresets[scopeKey]

    fun bindRoomTypes(scopeKey: String, preset: String?) {
        if (preset == null) roomTypePresets.remove(scopeKey) else roomTypePresets[scopeKey] = preset
        save()
    }

    fun roomBlockPresetFor(scopeKey: String): String? = roomBlockPresets[scopeKey]

    fun bindRoomBlocks(scopeKey: String, preset: String?) {
        if (preset == null) roomBlockPresets.remove(scopeKey) else roomBlockPresets[scopeKey] = preset
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
                for (room in roomBlockPresets.keys.toList()) {
                    if (roomBlockPresets[room] == from) roomBlockPresets[room] = to
                }
                if (defaultBlockPreset == from) defaultBlockPreset = to
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
            activePalette = root.get("activePalette")?.asString ?: DEFAULT_PRESET
            brushRadius = root.get("brushRadius")?.asInt ?: 1
            paintSound = root.get("paintSound")?.asBoolean ?: true
            showHud = root.get("showHud")?.asBoolean ?: true
            showHints = root.get("showHints")?.asBoolean ?: true
            // Defaults false so configs written before the hint existed still get it once.
            seenIntro = root.get("seenIntro")?.asBoolean ?: false
            dungeonRoomScope = root.get("dungeonRoomScope")?.asBoolean ?: true
            areaOutlineColor = root.get("areaOutlineColor")?.asInt ?: DEFAULT_AREA_OUTLINE
            areaFillColor = root.get("areaFillColor")?.asInt ?: DEFAULT_AREA_FILL

            roomTypePresets.clear()
            root.getAsJsonObject("roomTypePresets")?.entrySet()?.forEach { (room, preset) ->
                roomTypePresets[room] = preset.asString
            }

            roomBlockPresets.clear()
            root.getAsJsonObject("roomBlockPresets")?.entrySet()?.forEach { (room, preset) ->
                roomBlockPresets[room] = preset.asString
            }

            roomPalettePresets.clear()
            root.getAsJsonObject("roomPalettePresets")?.entrySet()?.forEach { (room, preset) ->
                roomPalettePresets[room] = preset.asString
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
            addProperty("activePalette", activePalette)
            addProperty("brushRadius", brushRadius)
            addProperty("paintSound", paintSound)
            addProperty("showHud", showHud)
            addProperty("showHints", showHints)
            addProperty("seenIntro", seenIntro)
            addProperty("dungeonRoomScope", dungeonRoomScope)
            addProperty("areaOutlineColor", areaOutlineColor)
            addProperty("areaFillColor", areaFillColor)

            val roomBindings = JsonObject()
            for ((room, preset) in roomTypePresets) roomBindings.addProperty(room, preset)
            add("roomTypePresets", roomBindings)

            val roomBlockBindings = JsonObject()
            for ((room, preset) in roomBlockPresets) roomBlockBindings.addProperty(room, preset)
            add("roomBlockPresets", roomBlockBindings)

            val roomPaletteBindings = JsonObject()
            for ((room, preset) in roomPalettePresets) roomPaletteBindings.addProperty(room, preset)
            add("roomPalettePresets", roomPaletteBindings)

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
