package com.maxisch.paint

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

    /** Whole-block-type presets, one flat source-to-donor map per file. */
    val blockTypeConfig: Path
        get() = root.resolve("block-type-config")

    val settingsFile: Path
        get() = root.resolve("settings.json")

    /** Where rules lived before presets existed; read once for migration, never written. */
    val legacyConfig: Path
        get() = FabricLoader.getInstance().configDir.resolve("austrianpainter")

    fun ensureDirectories() {
        root.createDirectories()
        blockConfig.createDirectories()
        blockTypeConfig.createDirectories()
    }

    /** Strips anything that could escape the folder or upset the filesystem. */
    fun sanitize(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
}
