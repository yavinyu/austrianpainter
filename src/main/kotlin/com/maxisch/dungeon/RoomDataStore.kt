package com.maxisch.dungeon

import com.google.gson.reflect.TypeToken
import com.maxisch.paint.ApJson
import com.maxisch.paint.ApLog.LOGGER
import com.maxisch.paint.ApPaths
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The room list, which maps a scanned column hash onto a room name.
 *
 * The list is maintained by NoammAddons and served as a versioned zip, so it is fetched once per
 * version and cached in `config/ap/data`. Everything about the fetch is best-effort: a stale cache
 * is better than no rooms, and no cache at all only means the room scope stays off - the rest of
 * the mod does not care.
 */
object RoomDataStore {

    private const val VERSION_URL = "https://api.noamm.org/na/data/version"
    private const val DOWNLOAD_URL = "https://api.noamm.org/na/data/download"
    private const val USER_AGENT = "austrianpainter-RoomDataStore"
    private const val TIMEOUT_MS = 15_000

    @Volatile
    var byCore: Map<Int, RoomData> = emptyMap()
        private set

    val loaded: Boolean
        get() = byCore.isNotEmpty()

    private val roomsFile: Path get() = ApPaths.dataDir.resolve("rooms.json")
    private val versionFile: Path get() = ApPaths.dataDir.resolve("version.txt")

    /** Reads the cache immediately, then refreshes it off-thread so startup never blocks on IO. */
    fun start() {
        readCache()
        Thread({ refresh() }, "austrianpainter-room-data").apply { isDaemon = true }.start()
    }

    private fun refresh() {
        runCatching {
            val remote = get(VERSION_URL).decodeToString().trim()
            val local = if (versionFile.exists()) versionFile.readText().trim() else null
            if (remote == local && roomsFile.exists()) return

            LOGGER.info("Fetching Catacombs room data (remote {}, local {})", remote, local ?: "none")
            val rooms = extractRooms(get(DOWNLOAD_URL)) ?: return

            ApPaths.ensureDirectories()
            Files.write(roomsFile, rooms)
            versionFile.writeText(remote)
            readCache()
        }.onFailure {
            LOGGER.warn("Could not refresh Catacombs room data, keeping the cached copy", it)
        }
    }

    private fun readCache() {
        if (!roomsFile.exists()) {
            LOGGER.warn("No Catacombs room data cached yet; dungeon room paint stays off until it downloads")
            return
        }

        runCatching {
            val type = object : TypeToken<List<RoomData>>() {}.type
            val rooms: List<RoomData> = ApJson.PLAIN.fromJson(roomsFile.readText(), type)

            val index = HashMap<Int, RoomData>()
            for (room in rooms) {
                if (room.isUnknown()) continue
                for (core in room.cores) index[core] = room
            }
            byCore = index
            LOGGER.info("Loaded {} Catacombs rooms ({} cores)", rooms.size, index.size)
        }.onFailure { LOGGER.error("Could not read {}", roomsFile, it) }
    }

    private fun get(url: String): ByteArray {
        val connection = URI.create(url).toURL().openConnection()
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        return connection.getInputStream().use { it.readBytes() }
    }

    /** The archive holds the whole NoammAddons data set; only `rooms.json` is of any use here. */
    private fun extractRooms(archive: ByteArray): ByteArray? {
        val temp = Files.createTempFile("ap-room-data-", ".zip")
        try {
            Files.copy(archive.inputStream(), temp, StandardCopyOption.REPLACE_EXISTING)
            ZipInputStream(Files.newInputStream(temp)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.substringAfterLast('/') == "rooms.json") {
                        return zip.readBytes()
                    }
                    entry = zip.nextEntry
                }
            }
            LOGGER.warn("Room data archive contained no rooms.json")
            return null
        } finally {
            temp.deleteIfExists()
        }
    }
}
