package com.maxisch.paint.settings

/**
 * Turns a block position into a well mixed seed, so a rule evaluated at bake time can draw from a
 * palette without the result changing every time the section is rebuilt.
 *
 * A [net.minecraft.util.RandomSource] cannot be used for that: a chunk section is baked any number
 * of times for the same block, and a fresh roll each time would make the colour flicker. The roll
 * has to be a pure function of where the block is.
 *
 * The mixing matters as much as the determinism. Multiplying each axis by its own odd constant and
 * running the sum through murmur3's finaliser keeps neighbouring columns in unrelated buckets; the
 * obvious `x * 31 + z` puts them in adjacent ones, which reads as diagonal stripes rather than as
 * noise once it picks a colour.
 *
 * Registry-free by design, so [PositionSeedTest] can cover it without a Minecraft bootstrap.
 */
internal object PositionSeed {

    // 2^32 divided by phi and by murmur3's two constants. Written negative because Kotlin has no
    // unsigned int literal that is usable in a const: 0x9E3779B1 = -0x61C8864F as a signed Int.
    private const val X_PRIME = -0x61C8864F
    private const val Y_PRIME = -0x7A143589
    private const val Z_PRIME = -0x3D4D51C3

    /** One seed per column, so a whole pillar draws the same block and keeps it while it moves. */
    fun column(x: Int, z: Int): Int = mix(x * X_PRIME + z * Z_PRIME)

    /** One seed per block, for a speckle. The pattern is fixed in space, not in the pillar. */
    fun block(x: Int, y: Int, z: Int): Int = mix(x * X_PRIME + y * Y_PRIME + z * Z_PRIME)

    /** murmur3's fmix32. */
    private fun mix(value: Int): Int {
        var hash = value
        hash = hash xor (hash ushr 16)
        hash *= -0x7A143595
        hash = hash xor (hash ushr 13)
        hash *= -0x3D4D51CB
        return hash xor (hash ushr 16)
    }
}
