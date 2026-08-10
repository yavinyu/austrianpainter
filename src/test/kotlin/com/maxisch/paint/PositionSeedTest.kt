package com.maxisch.paint

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The seed is what stops a palette-painted pillar from flickering, and a bad mixing constant is
 * invisible in code review - it only shows up in game as diagonal stripes. Both properties are
 * cheap to assert here, and neither needs a Minecraft bootstrap.
 */
class PositionSeedTest {

    @Test
    fun `the same position always gives the same seed`() {
        repeat(64) {
            val x = it * 7 - 200
            val z = it * 13 - 90
            assertEquals(PositionSeed.column(x, z), PositionSeed.column(x, z))
            assertEquals(PositionSeed.block(x, 169, z), PositionSeed.block(x, 169, z))
        }
    }

    @Test
    fun `a block seed varies down a column where a column seed does not`() {
        // This is the whole difference between the two seed modes: one pillar, 38 blocks.
        val band = 169..206
        val perBlock = band.map { PositionSeed.block(45, it, 44) }.toSet()
        assertEquals(band.count(), perBlock.size, "two heights in one column collided")

        val perColumn = band.map { PositionSeed.column(45, 44) }.toSet()
        assertEquals(1, perColumn.size)
    }

    @Test
    fun `neighbouring columns do not walk through the buckets in step`() {
        // The failure this catches: a weak mix makes seed(x+1, z) land one bucket along from
        // seed(x, z), so a palette paints diagonal bands instead of noise.
        val buckets = 8
        var stepped = 0
        for (x in 0 until 64) {
            for (z in 0 until 64) {
                val here = Math.floorMod(PositionSeed.column(x, z), buckets)
                val next = Math.floorMod(PositionSeed.column(x + 1, z), buckets)
                if (next == (here + 1) % buckets) stepped++
            }
        }
        // 1 in 8 pairs land one along by chance; anything near every pair means the mix is linear.
        assertTrue(stepped < 64 * 64 / 4, "neighbouring columns step through buckets: $stepped")
    }

    @Test
    fun `column seeds spread evenly across buckets`() {
        val buckets = 8
        val counts = IntArray(buckets)
        for (x in 0 until 64) {
            for (z in 0 until 64) counts[Math.floorMod(PositionSeed.column(x, z), buckets)]++
        }

        val expected = 64 * 64 / buckets
        for ((bucket, count) in counts.withIndex()) {
            assertTrue(
                abs(count - expected) < expected / 2,
                "bucket $bucket got $count of an expected $expected: ${counts.toList()}",
            )
        }
    }
}
