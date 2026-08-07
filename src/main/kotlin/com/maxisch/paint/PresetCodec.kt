package com.maxisch.paint

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.maxisch.paint.ApLog.LOGGER
import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Reads and writes both preset formats.
 *
 * Both files are meant to be opened and edited by hand, so parsing is deliberately forgiving: a bad
 * coordinate, an unknown block or a junk key is logged and skipped rather than failing the load. The
 * alternative - refusing to load - would lose someone's whole palette over one typo.
 */
object PresetCodec {

    // ---------------------------------------------------------------- positional

    private const val DIMENSIONS = "dimensions"
    private const val ROOMS = "rooms"

    /**
     * ```
     * {
     *   "dimensions": { "minecraft:overworld": { "minecraft:black_concrete_powder": ["112,15,0"] } },
     *   "rooms":      { "Water Board":        { "minecraft:black_concrete_powder": ["3,70,-5"] } }
     * }
     * ```
     *
     * Files written before rooms existed are a bare dimension map with no wrapper, so a root
     * without either section is read as one.
     */
    fun readBlocks(path: Path): BlockPreset {
        val preset = BlockPreset()
        if (path.notExists()) return preset

        val root = runCatching { JsonParser.parseString(path.readText()).asJsonObject }
            .onFailure { LOGGER.error("Could not parse {}", path, it) }
            .getOrNull() ?: return preset

        val dimensions = root.getAsJsonObject(DIMENSIONS)
        val rooms = root.getAsJsonObject(ROOMS)

        if (dimensions == null && rooms == null) {
            readDimensions(root, preset, path)
            return preset
        }

        dimensions?.let { readDimensions(it, preset, path) }
        rooms?.entrySet()?.forEach { (room, donors) ->
            readDonors(donors.asJsonObject, preset.forRoom(room), path)
        }
        return preset
    }

    private fun readDimensions(source: JsonObject, preset: BlockPreset, path: Path) {
        for ((dimensionId, donors) in source.entrySet()) {
            val dimension = Identifier.tryParse(dimensionId)
                ?.let { ResourceKey.create(Registries.DIMENSION, it) }
            if (dimension == null) {
                LOGGER.warn("Skipping unknown dimension key '{}' in {}", dimensionId, path)
                continue
            }
            readDonors(donors.asJsonObject, preset.forDimension(dimension), path)
        }
    }

    private fun readDonors(source: JsonObject, into: Long2ObjectMap<Block>, path: Path) {
        for ((blockId, coordinates) in source.entrySet()) {
            val block = block(blockId, path) ?: continue
            for (element in coordinates.asJsonArray) {
                val pos = parsePos(element.asString, path) ?: continue
                into.put(pos.asLong(), block)
            }
        }
    }

    fun writeBlocks(path: Path, preset: BlockPreset) {
        val dimensions = preset.dimensions.entries
            .filter { it.value.isNotEmpty() }
            .associate { (dimension, positions) -> dimension.identifier().toString() to positions }
        val rooms = preset.rooms.entries
            .filter { it.value.isNotEmpty() }
            .associate { (room, positions) -> room to positions }

        val text = buildString {
            append("{\n")
            append("  \"").append(DIMENSIONS).append("\": ")
            appendSection(dimensions, 1)
            append(",\n")
            append("  \"").append(ROOMS).append("\": ")
            appendSection(rooms, 1)
            append('\n')
            append("}\n")
        }

        path.createParentDirectories()
        path.writeText(text)
    }

    /**
     * Written by hand rather than through Gson so the coordinate arrays stay on one line. Gson's
     * pretty printer puts every array element on its own line, which triples the file length of a
     * large preset for no benefit.
     */
    private fun StringBuilder.appendSection(
        groups: Map<String, Long2ObjectMap<Block>>,
        depth: Int,
    ) {
        if (groups.isEmpty()) {
            append("{}")
            return
        }

        val outer = "  ".repeat(depth)
        val inner = "  ".repeat(depth + 1)

        append("{\n")
        groups.entries.forEachIndexed { groupIndex, (key, positions) ->
            append(inner).append('"').append(key).append("\": {\n")

            val byDonor = groupByDonor(positions)
            byDonor.entries.forEachIndexed { donorIndex, (block, coordinates) ->
                append(inner).append("  \"").append(BuiltInRegistries.BLOCK.getKey(block)).append("\": [")
                coordinates.forEachIndexed { index, coordinate ->
                    if (index > 0) append(", ")
                    append('"').append(coordinate).append('"')
                }
                append(']')
                if (donorIndex < byDonor.size - 1) append(',')
                append('\n')
            }

            append(inner).append('}')
            if (groupIndex < groups.size - 1) append(',')
            append('\n')
        }
        append(outer).append('}')
    }

    private fun groupByDonor(positions: Long2ObjectMap<Block>): Map<Block, List<String>> {
        val grouped = LinkedHashMap<Block, MutableList<String>>()
        val cursor = BlockPos.MutableBlockPos()
        for (entry in positions.long2ObjectEntrySet()) {
            cursor.set(BlockPos.of(entry.longKey))
            grouped.getOrPut(entry.value) { mutableListOf() }
                .add("${cursor.x},${cursor.y},${cursor.z}")
        }
        return grouped
    }

    // ---------------------------------------------------------------- types

    /** `{ "minecraft:oak_stairs": "minecraft:diamond_block" }` — flat, applies in every dimension. */
    fun readTypes(path: Path): TypePreset {
        val preset = TypePreset()
        if (path.notExists()) return preset

        val root = runCatching { JsonParser.parseString(path.readText()).asJsonObject }
            .onFailure { LOGGER.error("Could not parse {}", path, it) }
            .getOrNull() ?: return preset

        for ((sourceId, donorId) in root.entrySet()) {
            val source = block(sourceId, path) ?: continue
            val donor = block(donorId.asString, path) ?: continue
            preset.map[source] = donor
        }
        return preset
    }

    fun writeTypes(path: Path, preset: TypePreset) {
        val root = JsonObject()
        for ((source, donor) in preset.map) {
            root.addProperty(
                BuiltInRegistries.BLOCK.getKey(source).toString(),
                BuiltInRegistries.BLOCK.getKey(donor).toString(),
            )
        }
        path.createParentDirectories()
        path.writeText(ApJson.PRETTY.toJson(root) + "\n")
    }

    // ---------------------------------------------------------------- palettes

    /** `{ "minecraft:stone": 70, "minecraft:cobblestone": 20 }` — relative draw weights. */
    fun readPalette(path: Path): PalettePreset {
        val preset = PalettePreset()
        if (path.notExists()) return preset

        val root = runCatching { JsonParser.parseString(path.readText()).asJsonObject }
            .onFailure { LOGGER.error("Could not parse {}", path, it) }
            .getOrNull() ?: return preset

        for ((blockId, weight) in root.entrySet()) {
            val block = block(blockId, path) ?: continue
            val parsed = runCatching { weight.asInt }.getOrElse {
                LOGGER.warn("Skipping malformed weight for '{}' in {}", blockId, path)
                return@getOrElse PalettePreset.MIN_WEIGHT
            }
            preset.weights[block] = parsed.coerceIn(PalettePreset.MIN_WEIGHT, PalettePreset.MAX_WEIGHT)
        }
        return preset
    }

    fun writePalette(path: Path, preset: PalettePreset) {
        val root = JsonObject()
        for ((block, weight) in preset.weights) {
            root.addProperty(BuiltInRegistries.BLOCK.getKey(block).toString(), weight)
        }
        path.createParentDirectories()
        path.writeText(ApJson.PRETTY.toJson(root) + "\n")
    }

    // ---------------------------------------------------------------- shared

    private fun block(id: String, path: Path): Block? {
        val identifier = Identifier.tryParse(id)
        if (identifier == null || !BuiltInRegistries.BLOCK.containsKey(identifier)) {
            LOGGER.warn("Skipping unknown block '{}' in {}", id, path)
            return null
        }
        return BuiltInRegistries.BLOCK.getValue(identifier)
    }

    private fun parsePos(raw: String, path: Path): BlockPos? {
        val parts = raw.split(',')
        if (parts.size != 3) {
            LOGGER.warn("Skipping malformed coordinate '{}' in {}", raw, path)
            return null
        }
        val x = parts[0].trim().toIntOrNull()
        val y = parts[1].trim().toIntOrNull()
        val z = parts[2].trim().toIntOrNull()
        if (x == null || y == null || z == null) {
            LOGGER.warn("Skipping malformed coordinate '{}' in {}", raw, path)
            return null
        }
        return BlockPos(x, y, z)
    }
}
