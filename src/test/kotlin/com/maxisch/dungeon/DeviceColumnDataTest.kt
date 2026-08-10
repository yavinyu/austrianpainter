package com.maxisch.dungeon

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the bundled coordinate file, which is the one part of the device column feature that can
 * break without any code changing - and whose failure mode in game is "nothing happens", with half
 * a dozen equally plausible causes.
 *
 * The band assertion is the important one. Skipping phase detection is only safe because every band
 * sits inside the phase-2 slab; a re-extracted file that moved a base would quietly make the layer
 * reach into another phase's geometry.
 */
class DeviceColumnDataTest {

    /** Upstream derives the F7 phase from player Y: phase 2 is the slab between these. */
    private val phaseTwoLow = 155
    private val phaseTwoHigh = 210

    @BeforeTest
    fun load() {
        DeviceColumnData.load()
    }

    @Test
    fun `the bundled file is on the classpath and parses`() {
        assertTrue(DeviceColumnData.loaded, "device_columns.json did not load")
        assertEquals(148, DeviceColumnData.columnCount)
    }

    @Test
    fun `every array named by a device rule has columns`() {
        for (key in listOf("GreenArray", "YellowArray", "PurpleArray", "RedArray")) {
            assertTrue(DeviceColumnData.points(key).isNotEmpty(), "$key has no columns")
        }
    }

    @Test
    fun `the attribution key is not read as an array`() {
        assertEquals(4, DeviceColumnData.byArray.size)
    }

    @Test
    fun `every band stays inside the phase two slab`() {
        for ((key, points) in DeviceColumnData.byArray) {
            for (point in points) {
                val top = point.y + DeviceColumnData.COLUMN_HEIGHT
                assertTrue(
                    point.y >= phaseTwoLow && top <= phaseTwoHigh,
                    "$key column ${point.x},${point.z} spans y ${point.y}..$top, outside phase 2",
                )
            }
        }
    }

    @Test
    fun `no two columns share an x and z`() {
        val seen = HashSet<Pair<Int, Int>>()
        for (points in DeviceColumnData.byArray.values) {
            for (point in points) {
                assertTrue(seen.add(point.x to point.z), "duplicate column at ${point.x},${point.z}")
            }
        }
    }
}
