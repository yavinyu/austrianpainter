package com.maxisch.paint.preset

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import com.maxisch.paint.settings.ApPaths
import com.maxisch.paint.rule.displayName

/** One store per preset folder. */
object PresetStores {

    val blocks = PresetStore(
        folder = { ApPaths.blockConfig },
        reader = PresetCodec::readBlocks,
        writer = PresetCodec::writeBlocks,
        empty = { BlockPreset() },
        describe = { it.size },
        lines = { preset ->
            preset.dimensions.map { (dimension, positions) ->
                Component.translatable(
                    "austrianpainter.presets.line_positions",
                    dimension.identifier().toString(),
                    positions.size,
                )
            }
        },
        render = PresetCodec::renderBlocks,
        parse = PresetCodec::parseBlocks,
    )

    val rooms = PresetStore(
        folder = { ApPaths.roomConfig },
        reader = PresetCodec::readRoomBlocks,
        writer = PresetCodec::writeRoomBlocks,
        empty = { RoomBlockPreset() },
        describe = { it.size },
        lines = ::keyedLines,
        render = PresetCodec::renderKeyedBlocks,
        parse = PresetCodec::parseKeyedBlocks,
    )

    val bosses = PresetStore(
        folder = { ApPaths.blockBossConfig },
        reader = PresetCodec::readBossBlocks,
        writer = PresetCodec::writeBossBlocks,
        empty = { RoomBlockPreset() },
        describe = { it.size },
        lines = ::keyedLines,
        render = PresetCodec::renderKeyedBlocks,
        parse = PresetCodec::parseKeyedBlocks,
    )

    val types = PresetStore(
        folder = { ApPaths.blockTypeConfig },
        reader = PresetCodec::readTypes,
        writer = PresetCodec::writeTypes,
        empty = { TypePreset() },
        describe = { it.size },
        lines = { preset ->
            preset.map.map { (source, donor) ->
                Component.translatable("austrianpainter.rule.type", source.name, donor.name)
            }
        },
        render = PresetCodec::renderTypes,
        parse = PresetCodec::parseTypes,
    )

    val palettes = PresetStore(
        folder = { ApPaths.paletteConfig },
        reader = PresetCodec::readPalette,
        writer = PresetCodec::writePalette,
        empty = { PalettePreset() },
        describe = { it.size },
        lines = { preset ->
            val total = preset.totalWeight().coerceAtLeast(1)
            preset.weights.map { (block, weight) ->
                Component.translatable(
                    "austrianpainter.presets.line_weight",
                    block.name,
                    weight,
                    weight * 100 / total,
                )
            }
        },
        render = PresetCodec::renderPalette,
        parse = PresetCodec::parsePalette,
    )

    val rulesets = PresetStore(
        folder = { ApPaths.rulesetConfig },
        reader = PresetCodec::readRuleset,
        writer = PresetCodec::writeRuleset,
        empty = { RulesetPreset() },
        describe = { it.size },
        lines = { preset ->
            preset.map.map { (selector, target) ->
                Component.translatable(
                    "austrianpainter.presets.line_rule",
                    selector.displayName(),
                    target.displayName(),
                )
            }
        },
        render = PresetCodec::renderRuleset,
        parse = PresetCodec::parseRuleset,
    )

    /** Dungeon-room and boss presets are the same shape, so they describe themselves the same way. */
    private fun keyedLines(preset: RoomBlockPreset): List<Component> =
        preset.positions.map { (key, positions) ->
            Component.translatable("austrianpainter.presets.line_positions", key, positions.size)
        }

    /** The store a [PresetKind] names. */
    fun of(kind: PresetKind): PresetStore<*> = when (kind) {
        PresetKind.BLOCKS -> blocks
        PresetKind.ROOMS -> rooms
        PresetKind.BOSSES -> bosses
        PresetKind.TYPES -> types
        PresetKind.PALETTES -> palettes
        PresetKind.RULESETS -> rulesets
    }
}
