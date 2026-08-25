package com.maxisch.paint

import com.maxisch.paint.rule.BossZone
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `ZoneBounds` is min-first and `contains` tests `in min..max`, so a pair written max-first matches
 * nothing at all and does it silently. The S2 crusher's two boxes were given with their Y corners
 * the wrong way round, which is exactly the mistake this guards.
 */
class ZoneBoundsTest {

    private fun contains(zone: BossZone, x: Int, y: Int, z: Int) =
        zone.bounds.any { it.contains(x, y, z) }

    @Test
    fun `both S2 crusher boxes contain their own corners`() {
        for ((x, z) in listOf(64 to 121, 66 to 125, 59 to 121, 61 to 125)) {
            for (y in listOf(109, 112)) {
                assertTrue(contains(BossZone.CRUSHER_S2, x, y, z), "($x, $y, $z) should be in the zone")
            }
        }
    }

    @Test
    fun `the gap between the two S2 crusher boxes is not in the zone`() {
        assertFalse(contains(BossZone.CRUSHER_S2, 62, 110, 123))
        assertFalse(contains(BossZone.CRUSHER_S2, 63, 110, 123))
    }

    @Test
    fun `outside the S2 crusher's Y range is not in the zone`() {
        assertFalse(contains(BossZone.CRUSHER_S2, 65, 108, 123))
        assertFalse(contains(BossZone.CRUSHER_S2, 65, 113, 123))
    }

    @Test
    fun `no zone was written with an inverted corner pair`() {
        // An inverted pair contains nothing, so its own declared corners failing is the tell.
        for (zone in BossZone.entries) {
            for (box in zone.bounds) {
                val (min, max) = box.corners()
                assertTrue(
                    box.contains(min.x, min.y, min.z) && box.contains(max.x, max.y, max.z),
                    "${zone.key} has a box whose own corners fall outside it",
                )
            }
        }
    }
}
