package com.maxisch.dungeon

import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.maxisch.paint.settings.ApJson
import com.maxisch.paint.settings.ApLog.LOGGER

/** One pillar's base block. The pillar rises from here, so the paint rule covers a band above it. */
data class DevicePoint(val x: Int, val y: Int, val z: Int)

/**
 * Where the floor-7 phase-2 device pillars stand, per array.
 *
 * Unlike the room list this is bundled rather than downloaded: it is the fixed geometry of one
 * arena that cannot change without Hypixel rebuilding the floor, and bundling means the feature
 * works offline and on the very first launch. The file is a copy of NoammAddons'
 * `iHateDioriteBlocks.json` (CC0), which is also where [COLUMN_HEIGHT] comes from.
 *
 * Keys stay the raw JSON names so this layer needs no block registry and no enum - see
 * [com.maxisch.paint.DeviceArray], which maps them onto colours.
 */
object DeviceColumnData {

    /** How far above its base a pillar travels; upstream scans `base.y .. base.y + 37`. */
    const val COLUMN_HEIGHT = 37

    private const val PATH = "/assets/austrianpainter/dungeon/device_columns.json"

    @Volatile
    var byArray: Map<String, List<DevicePoint>> = emptyMap()
        private set

    val loaded: Boolean
        get() = byArray.isNotEmpty()

    val columnCount: Int
        get() = byArray.values.sumOf { it.size }

    fun points(arrayKey: String): List<DevicePoint> = byArray[arrayKey] ?: emptyList()

    /**
     * Read straight off the classloader rather than through the resource manager: this is code
     * data, not something a resource pack should be able to redefine, and it has to be available
     * before the first reload.
     */
    fun load() {
        val stream = javaClass.getResourceAsStream(PATH)
        if (stream == null) {
            LOGGER.error("Device column data missing from the jar at {}; the feature cannot run", PATH)
            return
        }

        runCatching {
            val root = stream.use { ApJson.PLAIN.fromJson(it.reader(), JsonObject::class.java) }
            val type = object : TypeToken<List<DevicePoint>>() {}.type

            val arrays = LinkedHashMap<String, List<DevicePoint>>()
            for ((key, value) in root.entrySet()) {
                // The file carries an attribution string beside the arrays, and a future version
                // may carry more, so anything that is not an array is simply not an array of
                // pillars.
                if (!value.isJsonArray) continue
                arrays[key] = ApJson.PLAIN.fromJson(value, type)
            }
            byArray = arrays
            LOGGER.info("Loaded {} device columns across {} arrays", columnCount, arrays.size)
        }.onFailure { LOGGER.error("Could not read {}", PATH, it) }
    }
}
