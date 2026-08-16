package com.maxisch.dungeon.room

import com.google.gson.annotations.SerializedName

/**
 * One Catacombs room prefab as described by `rooms.json`.
 *
 * Only the fields the paint scope needs are kept; the file also carries secret counts and
 * coordinates, which Gson simply ignores here.
 */
data class RoomData(
    val name: String,
    val type: RoomType = RoomType.NORMAL,
    val shape: RoomShape = RoomShape.UNKNOWN,
    /** Column hashes, one per 32x32 tile the prefab occupies, in no particular order. */
    val cores: List<Int> = emptyList(),
) {
    fun isUnknown(): Boolean = name == "Unknown" && shape == RoomShape.UNKNOWN
}

enum class RoomShape(val tileCount: Int) {
    @SerializedName("Unknown")
    UNKNOWN(0),

    @SerializedName("L")
    SL(3),

    @SerializedName("1x1")
    S1x1(1),

    @SerializedName("1x2")
    S2x1(2),

    @SerializedName("1x3")
    S3x1(3),

    @SerializedName("1x4")
    S4x1(4),

    @SerializedName("2x2")
    S2x2(4),
}

enum class RoomType {
    BLOOD, FAIRY, RARE, CHAMPION, PUZZLE, TRAP, NORMAL, ENTRANCE
}
