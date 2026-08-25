package com.maxisch.dungeon

import com.maxisch.dungeon.detect.SkyblockIsland
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The area string arrives from Hypixel's tab list, so the matching has to survive whatever
 * capitalisation and padding it comes wrapped in - and has to say "unknown" rather than guess when
 * Hypixel adds an island this list has never heard of.
 */
class SkyblockIslandTest {

    @Test
    fun `an exact area name matches`() {
        assertEquals(SkyblockIsland.DWARVEN_MINES, SkyblockIsland.byArea("Dwarven Mines"))
        assertEquals(SkyblockIsland.CRYSTAL_HOLLOWS, SkyblockIsland.byArea("Crystal Hollows"))
        assertEquals(SkyblockIsland.PRIVATE_ISLAND, SkyblockIsland.byArea("Private Island"))
    }

    @Test
    fun `capitalisation and padding do not matter`() {
        assertEquals(SkyblockIsland.HUB, SkyblockIsland.byArea("hub"))
        assertEquals(SkyblockIsland.THE_RIFT, SkyblockIsland.byArea("  THE RIFT  "))
    }

    @Test
    fun `an unknown area is null rather than a wrong guess`() {
        // A sub-area, which is what the sidebar carries - it must not resolve to an island.
        assertNull(SkyblockIsland.byArea("Royal Mines"))
        assertNull(SkyblockIsland.byArea("Village"))
        assertNull(SkyblockIsland.byArea(""))
    }

    @Test
    fun `the command accepts either the key or the display name`() {
        assertEquals(SkyblockIsland.SPIDERS_DEN, SkyblockIsland.byKeyOrArea("spiders_den"))
        assertEquals(SkyblockIsland.SPIDERS_DEN, SkyblockIsland.byKeyOrArea("Spider's Den"))
        assertNull(SkyblockIsland.byKeyOrArea("not_an_island"))
    }

    @Test
    fun `keys are unique - they are the binding key presets are stored under`() {
        val keys = SkyblockIsland.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
