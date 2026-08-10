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
     * without a `dimensions` section is read as one. A leftover `rooms` section is from before
     * dungeon-room and boss-room paint got their own preset kinds - see [migrateLegacyRooms].
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
        } else {
            dimensions?.let { readDimensions(it, preset, path) }
            rooms?.let { migrateLegacyRooms(it, path) }
        }
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

    private fun readRoot(path: Path): JsonObject? {
        if (path.notExists()) return null
        return runCatching { JsonParser.parseString(path.readText()).asJsonObject }
            .onFailure { LOGGER.error("Could not parse {}", path, it) }
            .getOrNull()
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

        val text = buildString {
            append("{\n")
            append("  \"").append(DIMENSIONS).append("\": ")
            appendSection(dimensions, 1)
            append('\n')
            append("}\n")
        }

        path.createParentDirectories()
        path.writeText(text)
    }

    // ---------------------------------------------------------------- dungeon rooms / bosses

    /** `{ "Water Board": { "minecraft:...": ["3,70,-5"] } }` — dungeon-room paint, flat at the root. */
    fun readRoomBlocks(path: Path): RoomBlockPreset = readKeyedBlocks(path)

    fun writeRoomBlocks(path: Path, preset: RoomBlockPreset) = writeKeyedBlocks(path, preset)

    /** `{ "B7": { "minecraft:...": ["3,70,-5"] } }` — boss-room paint, flat at the root. */
    fun readBossBlocks(path: Path): RoomBlockPreset = readKeyedBlocks(path)

    fun writeBossBlocks(path: Path, preset: RoomBlockPreset) = writeKeyedBlocks(path, preset)

    private fun readKeyedBlocks(path: Path): RoomBlockPreset {
        val preset = RoomBlockPreset()
        val root = readRoot(path) ?: return preset
        for ((key, donors) in root.entrySet()) {
            readDonors(donors.asJsonObject, preset.forKey(key), path)
        }
        return preset
    }

    private fun writeKeyedBlocks(path: Path, preset: RoomBlockPreset) {
        val groups = preset.positions.entries
            .filter { it.value.isNotEmpty() }
            .associate { (key, positions) -> key to positions }
        path.createParentDirectories()
        path.writeText(buildString { appendSection(groups, 0) } + "\n")
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
    fun readRuleset(path: Path): RulesetPreset {
        val preset = RulesetPreset()
        val root = readRoot(path) ?: return preset

        for ((key, value) in root.entrySet()) {
            val selector = selector(key, path) ?: continue
            val target = target(value.asString, path) ?: continue
            preset.map[selector] = target
        }
        return preset
    }

    fun writeRuleset(path: Path, preset: RulesetPreset) {
        val root = JsonObject()
        for ((selector, target) in preset.map) {
            root.addProperty(selectorKey(selector), targetValue(target))
        }
        path.createParentDirectories()
        path.writeText(ApJson.PRETTY.toJson(root) + "\n")
    }

    private fun selector(key: String, path: Path): AreaSelector? = when (key) {
        KEY_EVERYTHING -> AreaSelector.Everything
        KEY_UNPAINTED -> AreaSelector.Unpainted
        else -> {
            val cut = key.lastIndexOf(PAINT_SEPARATOR)
            val block = block(if (cut < 0) key else key.substring(0, cut), path)
            val paint = paintFilter(if (cut < 0) null else key.substring(cut + 1), path)
            if (block == null || paint == null) null else AreaSelector.Type(block, paint)
        }
    }

    private fun paintFilter(raw: String?, path: Path): PaintFilter? = when (raw) {
        null -> PaintFilter.AnyPaint
        PAINT_NONE -> PaintFilter.Unpainted
        else -> block(raw, path)?.let { PaintFilter.PaintedAs(it) }
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

    private fun target(raw: String, path: Path): AreaTarget? {
        if (raw.startsWith(PALETTE_PREFIX)) {
            val name = ApPaths.sanitize(raw.removePrefix(PALETTE_PREFIX))
            if (name.isEmpty()) {
                LOGGER.warn("Skipping blank palette name '{}' in {}", raw, path)
                return null
            }
            return AreaTarget.Palette(name)
        }
        return block(raw, path)?.let { AreaTarget.Donor(it) }
    }

    private fun targetValue(target: AreaTarget): String = when (target) {
        is AreaTarget.Donor -> BuiltInRegistries.BLOCK.getKey(target.block).toString()
        is AreaTarget.Palette -> PALETTE_PREFIX + target.name
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
