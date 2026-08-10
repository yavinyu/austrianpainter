package com.maxisch.paint

import com.maxisch.paint.ApLog.LOGGER
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.notExists

/**
 * One folder of presets and whichever of them is currently loaded.
 *
 * Generic over the preset type so every preset kind shares every bit of this; see [PresetStores]
 * for the five instances.
 */
class PresetStore<P : Any>(
    private val folder: () -> Path,
    private val reader: (Path) -> P,
    private val writer: (Path, P) -> Unit,
    private val empty: () -> P,
    private val describe: (P) -> Int,
) {

    var activeName: String = ApSettings.DEFAULT_PRESET
        private set

    var active: P = empty()
        private set

    fun path(name: String): Path = folder().resolve("${ApPaths.sanitize(name)}.json")

    fun exists(name: String): Boolean = Files.exists(path(name))

    fun list(): List<String> = runCatching {
        folder().listDirectoryEntries()
            .filter { it.extension == "json" }
            .map { it.nameWithoutExtension }
            .sorted()
    }.getOrElse { emptyList() }

    /** Preset names plus the active one, so a freshly bound name is always selectable. */
    fun listWithActive(): List<String> =
        (list() + activeName).distinct().sorted()

    /**
     * The named preset without making it active - for the places a rule names a preset in passing,
     * like an area ruleset pointing at a palette the player is not currently holding.
     */
    fun read(name: String): P {
        if (name == activeName) return active
        return runCatching { reader(path(name)) }
            .onFailure { LOGGER.error("Could not read preset '{}'", name, it) }
            .getOrElse { empty() }
    }

    fun entryCount(name: String): Int =
        if (name == activeName) describe(active) else runCatching { describe(reader(path(name))) }.getOrElse { 0 }

    // ---------------------------------------------------------------- active preset

    fun load(name: String) {
        val target = ApPaths.sanitize(name).ifEmpty { ApSettings.DEFAULT_PRESET }
        val file = path(target)

        active = if (file.notExists()) {
            // A binding can outlive its file; fall back rather than leaving a half-loaded state.
            if (target != ApSettings.DEFAULT_PRESET) {
                LOGGER.warn("Preset '{}' is missing, creating it", target)
            }
            empty()
        } else {
            runCatching { reader(file) }
                .onFailure { LOGGER.error("Could not load preset '{}'", target, it) }
                .getOrElse { empty() }
        }

        activeName = target
        if (file.notExists()) saveActive()
    }

    fun saveActive() {
        runCatching {
            ApPaths.ensureDirectories()
            writer(path(activeName), active)
        }.onFailure { LOGGER.error("Could not save preset '{}'", activeName, it) }
    }

    // ---------------------------------------------------------------- management

    /** Returns the sanitised name on success, null if it was blank or already taken. */
    fun create(name: String): String? {
        val clean = ApPaths.sanitize(name)
        if (clean.isEmpty() || exists(clean)) return null
        runCatching {
            ApPaths.ensureDirectories()
            writer(path(clean), empty())
        }.onFailure {
            LOGGER.error("Could not create preset '{}'", clean, it)
            return null
        }
        return clean
    }

    fun duplicate(from: String, to: String): String? {
        val clean = ApPaths.sanitize(to)
        if (clean.isEmpty() || exists(clean) || !exists(from)) return null
        runCatching {
            path(from).copyTo(path(clean))
        }.onFailure {
            LOGGER.error("Could not duplicate preset '{}'", from, it)
            return null
        }
        return clean
    }

    fun rename(from: String, to: String): String? {
        val clean = ApPaths.sanitize(to)
        if (clean.isEmpty() || exists(clean) || !exists(from)) return null
        runCatching {
            Files.move(path(from), path(clean))
        }.onFailure {
            LOGGER.error("Could not rename preset '{}'", from, it)
            return null
        }
        if (activeName == from) activeName = clean
        return clean
    }

    fun delete(name: String): Boolean = runCatching {
        Files.deleteIfExists(path(name))
    }.onFailure { LOGGER.error("Could not delete preset '{}'", name, it) }.getOrDefault(false)
}
