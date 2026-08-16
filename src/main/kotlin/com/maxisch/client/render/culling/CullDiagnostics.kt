package com.maxisch.client.render.culling

import java.util.concurrent.atomic.AtomicLong

/**
 * Counters for the paint-aware face culling, readable with `/ap cull`.
 *
 * A hole in a wall and a fix that never runs look identical from inside the game, and these split
 * the two: no checks at all means the culling hook is not on the path being used, while checks
 * without kept faces means it runs and still decides to hide the face.
 *
 * Deliberately just two counters. This sits in the chunk meshing path, so it must not allocate -
 * describing each decision as a string cost more than the decision itself.
 */
object CullDiagnostics {

    private val paintedChecks = AtomicLong()
    private val facesChanged = AtomicLong()

    @JvmStatic
    fun record(changed: Boolean) {
        paintedChecks.incrementAndGet()
        if (changed) facesChanged.incrementAndGet()
    }

    fun summary(): String =
        "painted culling checks=${paintedChecks.get()}, faces changed=${facesChanged.get()}"

    fun reset() {
        paintedChecks.set(0)
        facesChanged.set(0)
    }
}
