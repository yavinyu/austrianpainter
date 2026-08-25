package com.maxisch.paint

import com.maxisch.paint.settings.ApSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A boss zone's key is the prefix its rules are saved under, so renaming a zone orphans every rule
 * a player already had unless the loader rewrites the old prefix. The trailing dot is what keeps
 * `crusher_p1` - a different zone, in the other phase column - out of the rename.
 */
class ZoneKeyMigrationTest {

    @Test
    fun `the old P3 crusher prefix is rewritten`() {
        assertEquals(
            "s3_crusher.minecraft:polished_granite",
            ApSettings.migratedZoneKey("crusher.minecraft:polished_granite"),
        )
    }

    @Test
    fun `the P1 crusher is left alone`() {
        val key = "crusher_p1.minecraft:polished_granite"
        assertEquals(key, ApSettings.migratedZoneKey(key))
    }

    @Test
    fun `unrelated zones and already-migrated keys pass through`() {
        for (key in listOf(
            "s1.minecraft:sea_lantern",
            "s2.minecraft:redstone_lamp.lit",
            "pillars.minecraft:coal_block",
            "s3_crusher.minecraft:polished_granite",
            "s2_crusher.minecraft:polished_granite",
        )) {
            assertEquals(key, ApSettings.migratedZoneKey(key))
        }
    }
}
