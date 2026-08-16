package com.maxisch.paint.settings

import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Everything the mod writes lives under `config/ap`, which resolves to `run/config/ap` in the dev
 * client and `.minecraft/config/ap` in a real install.
 */
object ApPaths {

    val root: Path
        get() = FabricLoader.getInstance().configDir.resolve("ap")

    /** Positional paint presets, one file per preset, keyed inside by dimension. */
    val blockConfig: Path
        get() = root.resolve("block-config")

    /** Dungeon-room paint, its own preset kind independent of world and boss paint. */
    val roomConfig: Path
        get() = root.resolve("room-config")

    /** Boss-room paint, its own preset kind independent of world and dungeon-room paint. */
    val blockBossConfig: Path
        get() = root.resolve("block-boss-config")

    /**
     * Where a leftover `rooms`/boss entry from a pre-split block-config file migrates to, matched
     * by filename. Only used by the one-time migration in `PresetCodec.readBlocks`.
     */
    fun blockBossFileFor(blockConfigFile: Path): Path = blockBossConfig.resolve(blockConfigFile.fileName)

    /** Whole-block-type presets, one flat source-to-donor map per file. */
    val blockTypeConfig: Path
        get() = root.resolve("block-type-config")

    /** Weighted donor palettes, one flat block-to-weight map per file. */
    val paletteConfig: Path
        get() = root.resolve("palette-config")

    /** Area rulesets, one flat selector-to-target map per file. */
    val rulesetConfig: Path
        get() = root.resolve("ruleset-config")

    val settingsFile: Path
        get() = root.resolve("settings.json")

    /** Downloaded reference data - currently only the Catacombs room list. */
    val dataDir: Path
        get() = root.resolve("data")

    /** Where rules lived before presets existed; read once for migration, never written. */
    val legacyConfig: Path
        get() = FabricLoader.getInstance().configDir.resolve("austrianpainter")

    fun ensureDirectories() {
        root.createDirectories()
        blockConfig.createDirectories()
        roomConfig.createDirectories()
        blockBossConfig.createDirectories()
        blockTypeConfig.createDirectories()
        paletteConfig.createDirectories()
        rulesetConfig.createDirectories()
        dataDir.createDirectories()
    }

    /** Strips anything that could escape the folder or upset the filesystem. */
    fun sanitize(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
}
