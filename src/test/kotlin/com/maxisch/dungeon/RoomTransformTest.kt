package com.maxisch.dungeon

import net.minecraft.core.BlockPos
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The highest-value test in the repo: a sign error in here silently files every room-scoped paint
 * against the wrong corner, and the only symptom is paint appearing somewhere else next run.
 *
 * [BlockPos] is a plain value type, so none of this needs a Minecraft bootstrap.
 */
class RoomTransformTest {

    private val rotations = listOf(0, 90, 180, 270)

    @Test
    fun `world to relative and back is the identity at every rotation`() {
        val origin = BlockPos(112, 0, -48)
        val samples = listOf(
            BlockPos(112, 70, -48),
            BlockPos(120, 12, -60),
            BlockPos(97, 254, -33),
            BlockPos(-5, 0, 7),
        )

        for (rotation in rotations) {
            for (world in samples) {
                val relative = RoomTransform.toRelative(world, origin, rotation)
                assertEquals(
                    world,
                    RoomTransform.toWorld(relative, origin, rotation),
                    "round trip failed at rotation $rotation for $world",
                )
            }
        }
    }

    @Test
    fun `round trip survives a negative origin`() {
        val origin = BlockPos(-185, 0, -185)
        val world = BlockPos(-170, 71, -200)

        for (rotation in rotations) {
            val relative = RoomTransform.toRelative(world, origin, rotation)
            assertEquals(world, RoomTransform.toWorld(relative, origin, rotation))
        }
    }

    @Test
    fun `y is never touched by rotation`() {
        val pos = BlockPos(3, 137, -9)
        for (rotation in rotations) {
            assertEquals(137, RoomTransform.rotate(pos, rotation).y)
        }
    }

    @Test
    fun `four quarter turns return to the start`() {
        val pos = BlockPos(7, 64, -3)
        var turned = pos
        repeat(4) { turned = RoomTransform.rotate(turned, 90) }
        assertEquals(pos, turned)
    }

    @Test
    fun `rotation normalises out of range and negative degrees`() {
        val pos = BlockPos(7, 64, -3)
        assertEquals(RoomTransform.rotate(pos, 90), RoomTransform.rotate(pos, 450))
        assertEquals(RoomTransform.rotate(pos, 270), RoomTransform.rotate(pos, -90))
        assertEquals(pos, RoomTransform.rotate(pos, 360))
    }

    @Test
    fun `the origin corner maps to zero on the horizontal axes`() {
        val origin = BlockPos(64, 0, 64)
        for (rotation in rotations) {
            val relative = RoomTransform.toRelative(BlockPos(64, 70, 64), origin, rotation)
            assertEquals(0, relative.x)
            assertEquals(0, relative.z)
            assertEquals(70, relative.y)
        }
    }
}
