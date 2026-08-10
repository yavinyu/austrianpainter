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
 * Reads and writes every preset format.
 *
 * Every file is meant to be opened and edited by hand, so parsing is deliberately forgiving: a bad
 * coordinate, an unknown block or a junk key is logged and skipped rather than failing the load. The
 * alternative - refusing to load - would lose someone's whole palette over one typo.
 *
 * Each format has a `parse`/`render` pair over plain text and a thin `read`/`write` pair over a
 * [Path]. Text is the real interface: it is what a preset shared through the clipboard is. The
 * `source` threaded through the parsers is only ever a log label - a file path, or the word
 * "clipboard".
 */
object PresetCodec {

    // ---------------------------------------------------------------- positional

    private const val DIMENSIONS = "dimensions"
    private const val ROOMS = "rooms"

    private val BOSS_KEY = Regex("^B\\d+$")

    /**
     * ```
     * { "dimensions": { "minecraft:overworld": { "minecraft:black_concrete_powder": ["112,15,0"] } } }
     * ```
     *
     * Files written before rooms existed are a bare dimension map with no wrapper, so a root
     * without a `dimensions` section is read as one.
     */
    fun parseBlocks(text: String, source: Any): BlockPreset {
        val preset = BlockPreset()
        val root = parseRoot(text, source) ?: return preset

        val dimensions = root.getAsJsonObject(DIMENSIONS)
        if (dimensions == null && root.getAsJsonObject(ROOMS) == null) {
            readDimensions(root, preset, source)
        } else {
            dimensions?.let { readDimensions(it, preset, source) }
        }
        return preset
    }

    /** A leftover `rooms` section only a real file can carry; see [migrateLegacyRooms]. */
    fun readBlocks(path: Path): BlockPreset {
        if (path.notExists()) return BlockPreset()

        val text = path.readText()
        val preset = parseBlocks(text, path)
        parseRoot(text, path)?.getAsJsonObject(ROOMS)?.let { migrateLegacyRooms(it, path) }
        return preset
    }

    /**
     * One-time migration for a file written before dungeon-room and boss-room paint became their
     * own preset kinds: splits a leftover `rooms` object by key shape into the Rooms store
     * (`room-config`) or the Bosses store (`block-boss-config`), matched by this file's name - but
     * only when that target preset has no content yet, so a Rooms/Bosses preset someone already
     * started under the new kind is never overwritten by stale pre-split data.
     */
    private fun migrateLegacyRooms(rooms: JsonObject, path: Path) {
        val roomTarget = ApPaths.roomConfig.resolve(path.fileName)
        val bossTarget = ApPaths.blockBossFileFor(path)

        val roomPreset = RoomBlockPreset()
        val bossPreset = RoomBlockPreset()
        for ((room, donors) in rooms.entrySet()) {
            val into = if (BOSS_KEY.matches(room)) bossPreset.forKey(room) else roomPreset.forKey(room)
            readDonors(donors.asJsonObject, into, path)
        }

        if (!roomPreset.isEmpty() && roomTarget.notExists()) writeRoomBlocks(roomTarget, roomPreset)
        if (!bossPreset.isEmpty() && bossTarget.notExists()) writeBossBlocks(bossTarget, bossPreset)
    }

    private fun parseRoot(text: String, source: Any): JsonObject? =
        runCatching { JsonParser.parseString(text).asJsonObject }
            .onFailure { LOGGER.error("Could not parse {}", source, it) }
            .getOrNull()

    private fun readRoot(path: Path): JsonObject? {
        if (path.notExists()) return null
        return parseRoot(path.readText(), path)
    }

    private fun readDimensions(root: JsonObject, preset: BlockPreset, source: Any) {
        for ((dimensionId, donors) in root.entrySet()) {
            val dimension = Identifier.tryParse(dimensionId)
                ?.let { ResourceKey.create(Registries.DIMENSION, it) }
            if (dimension == null) {
                LOGGER.warn("Skipping unknown dimension key '{}' in {}", dimensionId, source)
                continue
            }
            readDonors(donors.asJsonObject, preset.forDimension(dimension), source)
        }
    }

    private fun readDonors(root: JsonObject, into: Long2ObjectMap<Block>, source: Any) {
        for ((blockId, coordinates) in root.entrySet()) {
            val block = block(blockId, source) ?: continue
            for (element in coordinates.asJsonArray) {
                val pos = parsePos(element.asString, source) ?: continue
                into.put(pos.asLong(), block)
            }
        }
    }

    fun renderBlocks(preset: BlockPreset): String {
        val dimensions = preset.dimensions.entries
            .filter { it.value.isNotEmpty() }
            .associate { (dimension, positions) -> dimension.identifier().toString() to positions }

        return buildString {
            append("{\n")
            append("  \"").append(DIMENSIONS).append("\": ")
            appendSection(dimensions, 1)
            append('\n')
            append("}\n")
        }
    }

    fun writeBlocks(path: Path, preset: BlockPreset) = write(path, renderBlocks(preset))

    // ---------------------------------------------------------------- dungeon rooms / bosses

    /** `{ "Water Board": { "minecraft:...": ["3,70,-5"] } }` — dungeon-room paint, flat at the root. */
    fun readRoomBlocks(path: Path): RoomBlockPreset = keyedFrom(readRoot(path), path)

    fun writeRoomBlocks(path: Path, preset: RoomBlockPreset) = write(path, renderKeyedBlocks(preset))

    /** `{ "B7": { "minecraft:...": ["3,70,-5"] } }` — boss-room paint, flat at the root. */
    fun readBossBlocks(path: Path): RoomBlockPreset = keyedFrom(readRoot(path), path)

    fun writeBossBlocks(path: Path, preset: RoomBlockPreset) = write(path, renderKeyedBlocks(preset))

    fun parseKeyedBlocks(text: String, source: Any): RoomBlockPreset =
        keyedFrom(parseRoot(text, source), source)

    private fun keyedFrom(root: JsonObject?, source: Any): RoomBlockPreset {
        val preset = RoomBlockPreset()
        if (root == null) return preset
        for ((key, donors) in root.entrySet()) {
            readDonors(donors.asJsonObject, preset.forKey(key), source)
        }
        return preset
    }

    fun renderKeyedBlocks(preset: RoomBlockPreset): String {
        val groups = preset.positions.entries
            .filter { it.value.isNotEmpty() }
            .associate { (key, positions) -> key to positions }
        return buildString { appendSection(groups, 0) } + "\n"
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
    fun parseTypes(text: String, source: Any): TypePreset {
        val preset = TypePreset()
        val root = parseRoot(text, source) ?: return preset

        for ((sourceId, donorId) in root.entrySet()) {
            val from = block(sourceId, source) ?: continue
            val donor = block(donorId.asString, source) ?: continue
            preset.map[from] = donor
        }
        return preset
    }

    fun readTypes(path: Path): TypePreset =
        if (path.notExists()) TypePreset() else parseTypes(path.readText(), path)

    fun renderTypes(preset: TypePreset): String {
        val root = JsonObject()
        for ((from, donor) in preset.map) {
            root.addProperty(
                BuiltInRegistries.BLOCK.getKey(from).toString(),
                BuiltInRegistries.BLOCK.getKey(donor).toString(),
            )
        }
        return ApJson.PRETTY.toJson(root) + "\n"
    }

    fun writeTypes(path: Path, preset: TypePreset) = write(path, renderTypes(preset))

    // ---------------------------------------------------------------- palettes

    /** `{ "minecraft:stone": 70, "minecraft:cobblestone": 20 }` — relative draw weights. */
    fun parsePalette(text: String, source: Any): PalettePreset {
        val preset = PalettePreset()
        val root = parseRoot(text, source) ?: return preset

        for ((blockId, weight) in root.entrySet()) {
            val block = block(blockId, source) ?: continue
            val parsed = runCatching { weight.asInt }.getOrElse {
                LOGGER.warn("Skipping malformed weight for '{}' in {}", blockId, source)
                return@getOrElse PalettePreset.MIN_WEIGHT
            }
            preset.weights[block] = parsed.coerceIn(PalettePreset.MIN_WEIGHT, PalettePreset.MAX_WEIGHT)
        }
        return preset
    }

    fun readPalette(path: Path): PalettePreset =
        if (path.notExists()) PalettePreset() else parsePalette(path.readText(), path)

    fun renderPalette(preset: PalettePreset): String {
        val root = JsonObject()
        for ((block, weight) in preset.weights) {
            root.addProperty(BuiltInRegistries.BLOCK.getKey(block).toString(), weight)
        }
        return ApJson.PRETTY.toJson(root) + "\n"
    }

    fun writePalette(path: Path, preset: PalettePreset) = write(path, renderPalette(preset))

    // ---------------------------------------------------------------- rulesets

    /** Reserved keys for the two selectors that are not a block type. */
    private const val KEY_EVERYTHING = "*all"
    private const val KEY_UNPAINTED = "*unpainted"

    /** A target value with this prefix names a palette rather than a donor block. */
    private const val PALETTE_PREFIX = "palette:"

    /**
     * Splits a source key into the block and the paint state it is narrowed to. Block ids never
     * contain it, so a key without one names the block whatever it currently looks like.
     */
    private const val PAINT_SEPARATOR = '@'
    private const val PAINT_NONE = "none"

    /**
     * ```
     * {
     *   "minecraft:stone_bricks": "minecraft:oak_planks",
     *   "minecraft:obsidian@none": "minecraft:white_wool",
     *   "minecraft:obsidian@minecraft:white_wool": "palette:mossy"
     * }
     * ```
     */
    fun parseRuleset(text: String, source: Any): RulesetPreset {
        val preset = RulesetPreset()
        val root = parseRoot(text, source) ?: return preset

        for ((key, value) in root.entrySet()) {
            val selector = selector(key, source) ?: continue
            val target = target(value.asString, source) ?: continue
            preset.map[selector] = target
        }
        return preset
    }

    fun readRuleset(path: Path): RulesetPreset =
        if (path.notExists()) RulesetPreset() else parseRuleset(path.readText(), path)

    fun renderRuleset(preset: RulesetPreset): String {
        val root = JsonObject()
        for ((selector, target) in preset.map) {
            root.addProperty(selectorKey(selector), targetValue(target))
        }
        return ApJson.PRETTY.toJson(root) + "\n"
    }

    fun writeRuleset(path: Path, preset: RulesetPreset) = write(path, renderRuleset(preset))

    private fun selector(key: String, source: Any): AreaSelector? = when (key) {
        KEY_EVERYTHING -> AreaSelector.Everything
        KEY_UNPAINTED -> AreaSelector.Unpainted
        else -> {
            val cut = key.lastIndexOf(PAINT_SEPARATOR)
            val block = block(if (cut < 0) key else key.substring(0, cut), source)
            val paint = paintFilter(if (cut < 0) null else key.substring(cut + 1), source)
            if (block == null || paint == null) null else AreaSelector.Type(block, paint)
        }
    }

    private fun paintFilter(raw: String?, source: Any): PaintFilter? = when (raw) {
        null -> PaintFilter.AnyPaint
        PAINT_NONE -> PaintFilter.Unpainted
        else -> block(raw, source)?.let { PaintFilter.PaintedAs(it) }
    }

    private fun selectorKey(selector: AreaSelector): String = when (selector) {
        AreaSelector.Everything -> KEY_EVERYTHING
        AreaSelector.Unpainted -> KEY_UNPAINTED
        is AreaSelector.Type -> {
            val id = BuiltInRegistries.BLOCK.getKey(selector.block).toString()
            when (val paint = selector.paint) {
                PaintFilter.AnyPaint -> id
                PaintFilter.Unpainted -> "$id$PAINT_SEPARATOR$PAINT_NONE"
                is PaintFilter.PaintedAs ->
                    "$id$PAINT_SEPARATOR${BuiltInRegistries.BLOCK.getKey(paint.donor)}"
            }
        }
    }

    private fun target(raw: String, source: Any): AreaTarget? {
        if (raw.startsWith(PALETTE_PREFIX)) {
            val name = ApPaths.sanitize(raw.removePrefix(PALETTE_PREFIX))
            if (name.isEmpty()) {
                LOGGER.warn("Skipping blank palette name '{}' in {}", raw, source)
                return null
            }
            return AreaTarget.Palette(name)
        }
        return block(raw, source)?.let { AreaTarget.Donor(it) }
    }

    private fun targetValue(target: AreaTarget): String = when (target) {
        is AreaTarget.Donor -> BuiltInRegistries.BLOCK.getKey(target.block).toString()
        is AreaTarget.Palette -> PALETTE_PREFIX + target.name
    }

    // ---------------------------------------------------------------- shared

    private fun write(path: Path, text: String) {
        path.createParentDirectories()
        path.writeText(text)
    }

    private fun block(id: String, source: Any): Block? {
        val identifier = Identifier.tryParse(id)
        if (identifier == null || !BuiltInRegistries.BLOCK.containsKey(identifier)) {
            LOGGER.warn("Skipping unknown block '{}' in {}", id, source)
            return null
        }
        return BuiltInRegistries.BLOCK.getValue(identifier)
    }

    private fun parsePos(raw: String, source: Any): BlockPos? {
        val parts = raw.split(',')
        if (parts.size != 3) {
            LOGGER.warn("Skipping malformed coordinate '{}' in {}", raw, source)
            return null
        }
        val x = parts[0].trim().toIntOrNull()
        val y = parts[1].trim().toIntOrNull()
        val z = parts[2].trim().toIntOrNull()
        if (x == null || y == null || z == null) {
            LOGGER.warn("Skipping malformed coordinate '{}' in {}", raw, source)
            return null
        }
        return BlockPos(x, y, z)
    }
}
