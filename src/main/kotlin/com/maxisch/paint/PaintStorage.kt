package com.maxisch.paint

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Owns the authored rules for the world the client is currently connected to, keeps them on disk,
 * and pushes the active dimension's rules into [PaintIndex].
 */
object PaintStorage {

    private val LOGGER = LoggerFactory.getLogger("austrianpainter")
    private val GSON = GsonBuilder().setPrettyPrinting().create()

    private val rules = LinkedHashMap<ResourceKey<Level>, MutableList<PaintRule>>()

    /** Identifies the world/server the rules belong to; null while not in a world. */
    var worldKey: String? = null
        private set

    private val configDir: Path
        get() = FabricLoader.getInstance().configDir.resolve("austrianpainter")

    // ---------------------------------------------------------------- lifecycle

    fun onJoinWorld() {
        val key = resolveWorldKey()
        if (key == worldKey) {
            refreshIndex()
            return
        }
        worldKey = key
        rules.clear()
        load()
        refreshIndex()
    }

    fun onLeaveWorld() {
        save()
        worldKey = null
        rules.clear()
        PaintIndex.clear()
    }

    /** Called when the player changes dimension, so the index tracks the right rule set. */
    fun refreshIndex() {
        PaintIndex.rebuild(currentRules())
    }

    // ---------------------------------------------------------------- queries

    fun currentDimension(): ResourceKey<Level>? = Minecraft.getInstance().level?.dimension()

    fun currentRules(): List<PaintRule> {
        val dim = currentDimension() ?: return emptyList()
        return rules[dim] ?: emptyList()
    }

    // ---------------------------------------------------------------- mutation

    fun add(rule: PaintRule) {
        val dim = currentDimension() ?: return
        val list = rules.getOrPut(dim) { mutableListOf() }
        // A newer rule for the same target area replaces the older one.
        list.removeAll { it.sameSubjectAs(rule) }
        list.add(rule)
        refreshIndex()
        save()
        markDirty(rule)
    }

    fun remove(rule: PaintRule) {
        val dim = currentDimension() ?: return
        val list = rules[dim] ?: return
        if (!list.remove(rule)) return
        refreshIndex()
        save()
        markDirty(rule)
    }

    /**
     * Brush stroke: paint many positions with one save and one chunk-rebuild pass. Returns how many
     * positions changed.
     */
    fun paintPositions(positions: Collection<BlockPos>, target: Block, paintSound: Boolean): Int {
        if (positions.isEmpty()) return 0
        val dim = currentDimension() ?: return 0
        val list = rules.getOrPut(dim) { mutableListOf() }

        val touched = positions.mapTo(LongOpenHashSet()) { it.asLong() }
        list.removeAll { it is PaintRule.OfPos && touched.contains(it.pos.asLong()) }
        positions.forEach { list.add(PaintRule.OfPos(it.immutable(), target, paintSound)) }

        refreshIndex()
        save()
        markRangeDirty(positions)
        return positions.size
    }

    /** Removes positional paints under a brush stroke. Region and type rules are left alone. */
    fun unpaintPositions(positions: Collection<BlockPos>): Int {
        if (positions.isEmpty()) return 0
        val dim = currentDimension() ?: return 0
        val list = rules[dim] ?: return 0

        val touched = positions.mapTo(LongOpenHashSet()) { it.asLong() }
        val removed = list.count { it is PaintRule.OfPos && touched.contains(it.pos.asLong()) }
        if (removed == 0) return 0

        list.removeAll { it is PaintRule.OfPos && touched.contains(it.pos.asLong()) }
        refreshIndex()
        save()
        markRangeDirty(positions)
        return removed
    }

    private fun markRangeDirty(positions: Collection<BlockPos>) {
        val level = Minecraft.getInstance().level ?: return
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var maxZ = Int.MIN_VALUE
        for (pos in positions) {
            if (pos.x < minX) minX = pos.x
            if (pos.y < minY) minY = pos.y
            if (pos.z < minZ) minZ = pos.z
            if (pos.x > maxX) maxX = pos.x
            if (pos.y > maxY) maxY = pos.y
            if (pos.z > maxZ) maxZ = pos.z
        }
        level.setSectionRangeDirty(
            SectionPos.blockToSectionCoord(minX) - 1,
            SectionPos.blockToSectionCoord(minY) - 1,
            SectionPos.blockToSectionCoord(minZ) - 1,
            SectionPos.blockToSectionCoord(maxX) + 1,
            SectionPos.blockToSectionCoord(maxY) + 1,
            SectionPos.blockToSectionCoord(maxZ) + 1,
        )
    }

    fun clearCurrentDimension() {
        val dim = currentDimension() ?: return
        val list = rules.remove(dim) ?: return
        refreshIndex()
        save()
        list.forEach(::markDirty)
    }

    private fun PaintRule.sameSubjectAs(other: PaintRule): Boolean = when {
        this is PaintRule.OfType && other is PaintRule.OfType -> source == other.source
        this is PaintRule.OfPos && other is PaintRule.OfPos -> pos == other.pos
        this is PaintRule.OfRegion && other is PaintRule.OfRegion -> min == other.min && max == other.max
        else -> false
    }

    // ---------------------------------------------------------------- re-render

    /** Schedules a chunk rebuild for everything the rule can affect. */
    private fun markDirty(rule: PaintRule) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        when (rule) {
            is PaintRule.OfPos -> {
                val sec = SectionPos.of(rule.pos)
                level.setSectionDirtyWithNeighbors(sec.x, sec.y, sec.z)
            }

            is PaintRule.OfRegion -> level.setSectionRangeDirty(
                SectionPos.blockToSectionCoord(rule.min.x) - 1,
                SectionPos.blockToSectionCoord(rule.min.y) - 1,
                SectionPos.blockToSectionCoord(rule.min.z) - 1,
                SectionPos.blockToSectionCoord(rule.max.x) + 1,
                SectionPos.blockToSectionCoord(rule.max.y) + 1,
                SectionPos.blockToSectionCoord(rule.max.z) + 1,
            )

            is PaintRule.OfType -> {
                // A type rule can hit anything, so rebuild the whole loaded view.
                val player = mc.player ?: return
                val radius = mc.options.renderDistance().get() + 1
                val sec = SectionPos.of(player.blockPosition())
                level.setSectionRangeDirty(
                    sec.x - radius, level.minSectionY, sec.z - radius,
                    sec.x + radius, level.maxSectionY, sec.z + radius,
                )
            }
        }
    }

    // ---------------------------------------------------------------- persistence

    private fun resolveWorldKey(): String {
        val mc = Minecraft.getInstance()
        val raw = mc.singleplayerServer?.worldData?.levelName
            ?: mc.currentServer?.ip
            ?: "unknown"
        return raw.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "unknown" }
    }

    private fun file(): Path? = worldKey?.let { configDir.resolve("$it.json") }

    private fun load() {
        val path = file() ?: return
        if (path.notExists()) return
        runCatching {
            val root = JsonParser.parseString(path.readText()).asJsonObject
            for ((dimId, value) in root.entrySet()) {
                val dim = Identifier.tryParse(dimId)?.let { ResourceKey.create(Registries.DIMENSION, it) }
                    ?: continue
                val list = mutableListOf<PaintRule>()
                for (element in value.asJsonArray) {
                    decode(element.asJsonObject)?.let(list::add)
                }
                if (list.isNotEmpty()) rules[dim] = list
            }
        }.onFailure { LOGGER.error("Failed to read paint rules from {}", path, it) }
    }

    private fun save() {
        val path = file() ?: return
        runCatching {
            if (rules.values.all { it.isEmpty() }) {
                Files.deleteIfExists(path)
                return
            }
            val root = JsonObject()
            for ((dim, list) in rules) {
                if (list.isEmpty()) continue
                val array = JsonArray()
                list.forEach { array.add(encode(it)) }
                root.add(dim.identifier().toString(), array)
            }
            configDir.createDirectories()
            path.writeText(GSON.toJson(root))
        }.onFailure { LOGGER.error("Failed to write paint rules to {}", path, it) }
    }

    private fun encode(rule: PaintRule): JsonObject = JsonObject().apply {
        addProperty("target", BuiltInRegistries.BLOCK.getKey(rule.target).toString())
        addProperty("sound", rule.paintSound)
        when (rule) {
            is PaintRule.OfType -> {
                addProperty("kind", "type")
                addProperty("source", BuiltInRegistries.BLOCK.getKey(rule.source).toString())
            }

            is PaintRule.OfPos -> {
                addProperty("kind", "pos")
                add("pos", encodePos(rule.pos))
            }

            is PaintRule.OfRegion -> {
                addProperty("kind", "region")
                add("min", encodePos(rule.min))
                add("max", encodePos(rule.max))
            }
        }
    }

    private fun decode(json: JsonObject): PaintRule? {
        val target = block(json, "target") ?: return null
        val sound = json.get("sound")?.asBoolean ?: false
        return when (json.get("kind")?.asString) {
            "type" -> block(json, "source")?.let { PaintRule.OfType(it, target, sound) }
            "pos" -> decodePos(json, "pos")?.let { PaintRule.OfPos(it, target, sound) }
            "region" -> {
                val min = decodePos(json, "min") ?: return null
                val max = decodePos(json, "max") ?: return null
                PaintRule.OfRegion(min, max, target, sound)
            }

            else -> null
        }
    }

    private fun block(json: JsonObject, key: String): Block? {
        val id = json.get(key)?.asString?.let(Identifier::tryParse) ?: return null
        return BuiltInRegistries.BLOCK.getValue(id)
    }

    private fun encodePos(pos: BlockPos) = JsonArray().apply {
        add(pos.x); add(pos.y); add(pos.z)
    }

    private fun decodePos(json: JsonObject, key: String): BlockPos? {
        val array = json.get(key)?.asJsonArray ?: return null
        if (array.size() != 3) return null
        return BlockPos(array[0].asInt, array[1].asInt, array[2].asInt)
    }
}
