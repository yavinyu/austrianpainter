package com.maxisch.paint.session

import net.minecraft.core.BlockPos

/**
 * Which part of the selected box an area apply actually touches.
 *
 * Pure arithmetic on the box and the position - no world reads - so the same test can gate the scan
 * counts and the apply. That is the point: the list has to show what the shape will really hit, or
 * the numbers lie.
 */
enum class AreaShape(val key: String) {

    SOLID("solid"),

    /** Every outward face of the box, walls plus floor plus ceiling. */
    SHELL("shell"),

    /** The four vertical sides, open top and bottom. */
    WALLS("walls"),

    FLOOR("floor"),

    CEILING("ceiling"),

    /** The largest ellipsoid that fits the box, so a non-cubic box gives a squashed sphere. */
    SPHERE("sphere");

    fun contains(x: Int, y: Int, z: Int, min: BlockPos, max: BlockPos): Boolean = when (this) {
        SOLID -> true
        SHELL -> onSide(x, y, z, min, max) || y == min.y || y == max.y
        WALLS -> onSide(x, y, z, min, max)
        FLOOR -> y == min.y
        CEILING -> y == max.y
        SPHERE -> inEllipsoid(x, y, z, min, max)
    }

    private fun onSide(x: Int, y: Int, z: Int, min: BlockPos, max: BlockPos): Boolean =
        x == min.x || x == max.x || z == min.z || z == max.z

    private fun inEllipsoid(x: Int, y: Int, z: Int, min: BlockPos, max: BlockPos): Boolean {
        val dx = axis(x, min.x, max.x)
        val dy = axis(y, min.y, max.y)
        val dz = axis(z, min.z, max.z)
        return dx * dx + dy * dy + dz * dz <= 1.0
    }

    /** Position on this axis as -1..1 from the box centre; a one-block axis is always centred. */
    private fun axis(value: Int, min: Int, max: Int): Double {
        val radius = (max - min) / 2.0
        if (radius <= 0.0) return 0.0
        return (value - (min + max) / 2.0) / radius
    }
}
