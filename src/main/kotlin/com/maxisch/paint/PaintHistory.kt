package com.maxisch.paint

import com.maxisch.paint.ApLog.LOGGER
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.Block

/**
 * Bounded undo over [PaintRules].
 *
 * Every mutation records what it displaced before it displaces it, so undo is a plain write back
 * rather than a replay. Coordinates are stored in *slice* space - already room-relative where a room
 * is in scope - and the stack is cleared whenever the scope changes, which is what lets undo skip
 * re-projection entirely. The cost is that walking through a Catacombs doorway drops the history;
 * the alternative is carrying a scope per step and refusing undo when the room has moved, which is
 * where the subtle bugs live. The scope key is kept per step anyway, as a cheap assertion.
 *
 * Undo always rebuilds the whole loaded view rather than the touched range: slice coordinates would
 * have to be projected back to world space to compute that range, and undo is rare enough that the
 * full rebuild is the cheaper thing to be sure about.
 *
 * Redo is deliberately not implemented, but both step types invert cleanly if it is ever wanted.
 */
object PaintHistory {

    /** How many steps are kept. */
    const val MAX_DEPTH = 20

    /**
     * A single change larger than this is not recorded at all. `PaintArea.MAX_VOLUME` is 2,000,000,
     * so a full-size area apply is deliberately outside what can be undone.
     */
    const val MAX_POSITIONS_PER_STEP = 400_000

    /** Ceiling across the whole stack; the oldest steps are dropped to stay under it. */
    const val MAX_POSITIONS_TOTAL = 1_000_000

    sealed interface Step {
        val label: Component
        val cost: Int
    }

    /**
     * Prior state of a set of positions. Split in two rather than one nullable-valued map because
     * the dominant case is painting over virgin terrain, and a `long` set is about half the size of
     * a `long`-to-object map per entry.
     */
    class Positions(
        val scopeKey: String?,
        override val label: Component,
    ) : Step {
        val wasUnpainted = LongOpenHashSet()
        val wasPainted = Long2ObjectOpenHashMap<Block>()

        override val cost: Int
            get() = wasUnpainted.size + wasPainted.size

        fun record(key: Long, prior: Block?) {
            if (prior == null) wasUnpainted.add(key) else wasPainted.put(key, prior)
        }
    }

    class TypeRule(
        val source: Block,
        val prior: Block?,
        override val label: Component,
    ) : Step {
        override val cost: Int get() = 1
    }

    private val steps = ArrayDeque<Step>()

    /**
     * True when the last recorded change was refused for being too large. The screens read it right
     * after an apply so they can say why the undo button went dead.
     */
    var overflowed: Boolean = false
        private set

    val canUndo: Boolean get() = steps.isNotEmpty()

    val depth: Int get() = steps.size

    fun clear() {
        steps.clear()
        overflowed = false
    }

    // ---------------------------------------------------------------- recording

    /**
     * Opens a positional step, or returns null when [size] is over the per-step cap.
     *
     * Refusing clears the whole stack rather than just skipping this one change. After an unrecorded
     * apply every older step's "prior donor" no longer describes what is on screen, so undoing them
     * would restore a state that never existed - dropping them is the only honest option.
     */
    internal fun beginPositions(label: Component, size: Int): Positions? {
        overflowed = false
        if (size > MAX_POSITIONS_PER_STEP) {
            steps.clear()
            overflowed = true
            return null
        }
        return Positions(PaintSession.scope?.key, label)
    }

    internal fun commit(step: Step?) {
        if (step == null || step.cost == 0) return

        steps.addLast(step)
        while (steps.size > MAX_DEPTH) steps.removeFirst()
        // Evicting from the bottom is safe: older steps are strictly further back in time, so
        // dropping them only shortens how far back undo reaches.
        while (steps.size > 1 && steps.sumOf { it.cost } > MAX_POSITIONS_TOTAL) steps.removeFirst()
    }

    internal fun recordTypeRule(source: Block, prior: Block?, label: Component) {
        overflowed = false
        commit(TypeRule(source, prior, label))
    }

    // ---------------------------------------------------------------- undo

    /** Reverses the newest step. Always returns something to show the player. */
    fun undo(): Component {
        val step = steps.removeLastOrNull() ?: return Component.translatable("austrianpainter.undo.none")
        overflowed = false

        return when (step) {
            is Positions -> {
                if (step.scopeKey != PaintSession.scope?.key) {
                    // Should be impossible: every scope change clears the stack.
                    LOGGER.warn(
                        "Undo step was recorded in scope {} but the scope is now {}; skipping",
                        step.scopeKey, PaintSession.scope?.key,
                    )
                    return Component.translatable("austrianpainter.undo.none")
                }
                val restored = PaintRules.restorePositions(step)
                Component.translatable("austrianpainter.undo.positions", restored)
            }

            is TypeRule -> {
                PaintRules.restoreTypeRule(step)
                Component.translatable("austrianpainter.undo.type", step.source.name)
            }
        }
    }
}
