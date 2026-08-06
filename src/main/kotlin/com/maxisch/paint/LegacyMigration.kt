package com.maxisch.paint

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import org.slf4j.LoggerFactory
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.notExists
import kotlin.io.path.readText

/**
 * One-shot conversion of the pre-preset config folder.
 *
 * The old format was a per-world file holding an array of tagged rule objects. It is split here:
 * positional and region rules become a block preset, type rules become a block-type preset, and the
 * world is bound to both. Region rules are expanded to individual coordinates, because region mode
 * no longer exists but the blocks people painted with it must not vanish.
 *
 * The originals are left untouched. This path has never run in anger, so deleting someone's only
 * copy of their rules on the strength of it is not a trade worth making.
 */
object LegacyMigration {

    private val LOGGER = LoggerFactory.getLogger("austrianpainter")

    fun run() {
        val legacy = ApPaths.legacyConfig
        if (legacy.notExists()) return

        val files = runCatching { legacy.listDirectoryEntries() }.getOrNull()
            ?.filter { it.extension == "json" }
            ?: return
        if (files.isEmpty()) return

        LOGGER.info("Migrating {} legacy config file(s) from {}", files.size, legacy)

        for (file in files) {
            val name = ApPaths.sanitize(file.nameWithoutExtension)
            val blocks = BlockPreset()
            val types = TypePreset()

            val root = runCatching { JsonParser.parseString(file.readText()).asJsonObject }
                .onFailure { LOGGER.error("Could not parse legacy file {}", file, it) }
                .getOrNull() ?: continue

            for ((dimensionId, rules) in root.entrySet()) {
                val dimension = Identifier.tryParse(dimensionId)
                    ?.let { ResourceKey.create(Registries.DIMENSION, it) } ?: continue

                for (element in rules.asJsonArray) {
                    convert(element.asJsonObject, dimension, blocks, types, file.toString())
                }
            }

            if (!blocks.isEmpty()) {
                PresetCodec.writeBlocks(ApPaths.blockConfig.resolve("$name.json"), blocks)
            }
            if (!types.isEmpty()) {
                PresetCodec.writeTypes(ApPaths.blockTypeConfig.resolve("$name.json"), types)
            }

            ApSettings.bindDuringMigration(
                worldKey = name,
                blocks = if (blocks.isEmpty()) null else name,
                types = if (types.isEmpty()) null else name,
            )
        }
    }

    private fun convert(
        rule: JsonObject,
        dimension: ResourceKey<Level>,
        blocks: BlockPreset,
        types: TypePreset,
        source: String,
    ) {
        val target = block(rule.get("target")?.asString) ?: return

        when (rule.get("kind")?.asString) {
            "type" -> {
                val from = block(rule.get("source")?.asString) ?: return
                val existing = types.map.put(from, target)
                if (existing != null && existing != target) {
                    // Old type rules were per dimension; the new format is not.
                    LOGGER.warn(
                        "Legacy type rules for {} disagreed across dimensions in {}; keeping {}",
                        BuiltInRegistries.BLOCK.getKey(from), source, BuiltInRegistries.BLOCK.getKey(target),
                    )
                }
            }

            "pos" -> pos(rule, "pos")?.let {
                blocks.forDimension(dimension).put(it.asLong(), target)
            }

            "region" -> {
                val min = pos(rule, "min") ?: return
                val max = pos(rule, "max") ?: return
                val positions = blocks.forDimension(dimension)
                for (inside in BlockPos.betweenClosed(min, max)) {
                    positions.put(inside.asLong(), target)
                }
            }
        }
    }

    private fun block(id: String?): Block? {
        val identifier = id?.let(Identifier::tryParse) ?: return null
        if (!BuiltInRegistries.BLOCK.containsKey(identifier)) return null
        return BuiltInRegistries.BLOCK.getValue(identifier)
    }

    private fun pos(rule: JsonObject, key: String): BlockPos? {
        val array = rule.get(key)?.asJsonArray ?: return null
        if (array.size() != 3) return null
        return BlockPos(array[0].asInt, array[1].asInt, array[2].asInt)
    }
}
