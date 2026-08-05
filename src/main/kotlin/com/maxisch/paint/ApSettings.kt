package com.maxisch.paint

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.slf4j.LoggerFactory
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Which presets a world last used. Both kinds are bound independently. */
data class WorldBinding(var blocks: String, var types: String)

/** `config/ap/settings.json`. */
object ApSettings {

    private val LOGGER = LoggerFactory.getLogger("austrianpainter")
    private val GSON = GsonBuilder().setPrettyPrinting().create()

    const val DEFAULT_PRESET = "default"

    var defaultBlockPreset: String = DEFAULT_PRESET
    var defaultTypePreset: String = DEFAULT_PRESET
    var brushRadius: Int = 1
    var paintSound: Boolean = true
    var showHud: Boolean = true

    private val worldPresets = LinkedHashMap<String, WorldBinding>()

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
    fun renamePreset(blocks: Boolean, from: String, to: String) {
        for (bound in worldPresets.values) {
            if (blocks && bound.blocks == from) bound.blocks = to
            if (!blocks && bound.types == from) bound.types = to
        }
        if (blocks && defaultBlockPreset == from) defaultBlockPreset = to
        if (!blocks && defaultTypePreset == from) defaultTypePreset = to
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
            brushRadius = root.get("brushRadius")?.asInt ?: 1
            paintSound = root.get("paintSound")?.asBoolean ?: true
            showHud = root.get("showHud")?.asBoolean ?: true

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
            addProperty("brushRadius", brushRadius)
            addProperty("paintSound", paintSound)
            addProperty("showHud", showHud)

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
            ApPaths.settingsFile.writeText(GSON.toJson(root) + "\n")
        }.onFailure { LOGGER.error("Could not write {}", ApPaths.settingsFile, it) }
    }

    internal fun bindDuringMigration(worldKey: String, blocks: String?, types: String?) {
        val bound = binding(worldKey)
        blocks?.let { bound.blocks = it }
        types?.let { bound.types = it }
    }
}
