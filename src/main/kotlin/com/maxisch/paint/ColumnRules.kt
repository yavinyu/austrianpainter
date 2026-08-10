package com.maxisch.paint

import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet
import it.unimi.dsi.fastutil.objects.ReferenceSet
import net.minecraft.world.level.block.Block

/**
 * Paint rules that cover a vertical band of a fixed column and are matched on the block that is
 * actually there, rather than on a coordinate.
 *
 * This is what lets the F7 device pillars keep their colour while they move. A positional rule is
 * pinned to a coordinate, so a rising pillar would slide out from under it; a global type rule
 * would repaint every diorite block in the game. A column rule is neither: it is evaluated at bake
 * time against the live blockstate, so whatever diorite currently occupies the band is painted and
 * nothing else is - with no tick loop, and without the mod ever touching the world.
 *
 * See [DeviceColumns] for the only thing that builds one.
 */

/** What one column rule paints one source block as. */
internal sealed interface ColumnTarget {

    class Donor(val block: Block) : ColumnTarget

    /** Position-seeded palette draw. [perColumn] rolls once per column instead of per block. */
    class Rolled(val picker: PalettePreset.Picker, val perColumn: Boolean) : ColumnTarget
}

internal fun ColumnTarget.resolve(x: Int, y: Int, z: Int): Block = when (this) {
    is ColumnTarget.Donor -> block
    is ColumnTarget.Rolled ->
        picker.at(if (perColumn) PositionSeed.column(x, z) else PositionSeed.block(x, y, z))
}

/**
 * One y-band of one column, and what each source block inside it becomes.
 *
 * [next] chains the case of two bands sharing an x/z. The bundled data has no such overlap, so the
 * chain is one long in practice and the walk costs nothing; it exists so a future data file cannot
 * silently drop half its columns.
 */
internal class ColumnBand(
    private val minY: Int,
    private val maxY: Int,
    private val targets: Reference2ObjectMap<Block, ColumnTarget>,
    private val next: ColumnBand?,
) {
    fun paintAt(x: Int, y: Int, z: Int, block: Block): Block? {
        var band: ColumnBand? = this
        while (band != null) {
            if (y >= band.minY && y <= band.maxY) {
                val target = band.targets[block]
                if (target != null) return target.resolve(x, y, z)
            }
            band = band.next
        }
        return null
    }
}

/** The whole column layer of one [PaintIndex] snapshot: the columns, plus the guard that keeps
 *  them free everywhere else. */
internal class ColumnRules(
    val byColumn: Long2ObjectMap<ColumnBand>,
    /**
     * Tested before the column map is touched. It is one hash lookup that is false for every block
     * in the world that is not one of the handful of source types, which is what stops this layer
     * from costing anything outside the arena it applies to.
     */
    val sources: ReferenceSet<Block>,
) {
    val empty: Boolean = byColumn.isEmpty() || sources.isEmpty()

    companion object {
        val EMPTY = ColumnRules(Long2ObjectOpenHashMap(), ReferenceOpenHashSet())

        /**
         * Packs an x/z pair. Deliberately not [net.minecraft.core.BlockPos.asLong], which spends
         * bits on y and only gives x 26 of them.
         */
        fun key(x: Int, z: Int): Long = (x.toLong() and 0xFFFFFFFFL) or (z.toLong() shl 32)
    }
}
