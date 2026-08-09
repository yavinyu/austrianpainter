package com.maxisch.paint

/** The three preset folders. */
object PresetStores {

    val blocks = PresetStore(
        folder = { ApPaths.blockConfig },
        reader = PresetCodec::readBlocks,
        writer = PresetCodec::writeBlocks,
        empty = { BlockPreset() },
        describe = { it.size },
    )

    val rooms = PresetStore(
        folder = { ApPaths.roomConfig },
        reader = PresetCodec::readRoomBlocks,
        writer = PresetCodec::writeRoomBlocks,
        empty = { RoomBlockPreset() },
        describe = { it.size },
    )

    val bosses = PresetStore(
        folder = { ApPaths.blockBossConfig },
        reader = PresetCodec::readBossBlocks,
        writer = PresetCodec::writeBossBlocks,
        empty = { RoomBlockPreset() },
        describe = { it.size },
    )

    val types = PresetStore(
        folder = { ApPaths.blockTypeConfig },
        reader = PresetCodec::readTypes,
        writer = PresetCodec::writeTypes,
        empty = { TypePreset() },
        describe = { it.size },
    )

    val palettes = PresetStore(
        folder = { ApPaths.paletteConfig },
        reader = PresetCodec::readPalette,
        writer = PresetCodec::writePalette,
        empty = { PalettePreset() },
        describe = { it.size },
    )

    /** The store a [PresetKind] names. */
    fun of(kind: PresetKind): PresetStore<*> = when (kind) {
        PresetKind.BLOCKS -> blocks
        PresetKind.ROOMS -> rooms
        PresetKind.BOSSES -> bosses
        PresetKind.TYPES -> types
        PresetKind.PALETTES -> palettes
    }
}
