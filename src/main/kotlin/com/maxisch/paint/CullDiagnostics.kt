package com.maxisch.paint

import java.util.concurrent.atomic.AtomicLong

/**
 * Counters for the paint-aware face culling, readable with `/paintbrush cull`.
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
    private val facesKept = AtomicLong()

    @JvmStatic
    fun record(kept: Boolean) {
        paintedChecks.incrementAndGet()
        if (kept) facesKept.incrementAndGet()
    }

    fun summary(): String =
        "painted culling checks=${paintedChecks.get()}, faces kept=${facesKept.get()}"

    fun reset() {
        paintedChecks.set(0)
        facesKept.set(0)
    }
}
