package com.maxisch.paint

import net.minecraft.world.level.block.Block

/**
 * What an area rule points at inside the box.
 *
 * [Unpainted] is deliberately not a block type: the scan reads live world blockstates, so a block
 * that has already been repainted still reports its original type. "Unchanged" therefore has to be
 * asked of [PaintIndex], not of the world.
 *
 * A position can match more than one selector at once, so an apply resolves them in a fixed
 * precedence - see `AreaScan.positionsFor`.
 */
sealed interface AreaSelector {

    /** Every non-air block in the box. */
    data object Everything : AreaSelector

    /** Every non-air block in the box that nothing has painted yet. */
    data object Unpainted : AreaSelector

    /** One world block type, narrowed by what it currently renders as. */
    data class Type(val block: Block, val paint: PaintFilter = PaintFilter.AnyPaint) : AreaSelector
}

/**
 * How much a [AreaSelector.Type] cares about the paint already on a block.
 *
 * The scan lists each block type once per paint state, so obsidian left alone and obsidian already
 * turned to wool are two rows that can be aimed somewhere different. [AnyPaint] is what a
 * hand-written ruleset key gets when it names no paint state, so a rule written by hand keeps
 * matching a block type however it currently looks.
 */
sealed interface PaintFilter {

    data object AnyPaint : PaintFilter

    data object Unpainted : PaintFilter

    data class PaintedAs(val donor: Block) : PaintFilter
}

/** What the blocks a selector matched should be repainted as. */
sealed interface AreaTarget {

    /** One donor for every matched position. */
    data class Donor(val block: Block) : AreaTarget

    /** A weighted draw from the named palette, one roll per matched position. */
    data class Palette(val name: String) : AreaTarget
}
