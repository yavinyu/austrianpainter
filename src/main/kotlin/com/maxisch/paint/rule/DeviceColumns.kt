package com.maxisch.paint.rule

import com.maxisch.dungeon.DeviceColumnData
import com.maxisch.dungeon.DevicePoint
import com.maxisch.dungeon.detect.DungeonLocation
import com.maxisch.paint.settings.ApLog.LOGGER
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import com.maxisch.paint.settings.ApSettings
import com.maxisch.paint.preset.PresetStores

/**
 * The four device arrays in the floor-7 phase-2 arena. The colour is the point: it is what tells
 * you at a glance which group a pillar belongs to, so each array's default donor is its own glass.
 */
enum class DeviceArray(val key: String, val jsonKey: String, val defaultDonor: Block) {
    GREEN("green", "GreenArray", Blocks.GREEN_STAINED_GLASS),
    YELLOW("yellow", "YellowArray", Blocks.YELLOW_STAINED_GLASS),
    PURPLE("purple", "PurpleArray", Blocks.PURPLE_STAINED_GLASS),
    RED("red", "RedArray", Blocks.RED_STAINED_GLASS),
}

/** The blocks a pillar is actually made of, each aimed independently. */
enum class DeviceSource(val key: String, val block: Block) {
    DIORITE("diorite", Blocks.DIORITE),
    POLISHED("polished_diorite", Blocks.POLISHED_DIORITE),
}

/**
 * Repaints the floor-7 phase-2 device pillars without touching the world.
 *
 * The pillars move, which is the whole difficulty. Painting them by coordinate would leave the
 * colour behind the moment one rises, and a global diorite type rule would repaint the entire
 * game. So this builds a [ColumnRules] layer instead: fixed columns, a y band, matched on the live
 * block. Because [PaintIndex.paintAt] is evaluated while a chunk section bakes, and a moving pillar
 * already dirties its sections through the server's own block updates, the colour follows the
 * pillar with no tick loop of any kind - unlike the NoammAddons feature this is a port of, which
 * has to rescan 38 blocks per column every tick because it works by `setBlock`.
 *
 * One consequence worth knowing: with the layer live, arena diorite shows up in the area tab's scan
 * as already painted, and an area Replace aimed at that row would write *positional* rules, which
 * outrank these and are pinned to coordinates - so that paint would stay behind when the pillar
 * moved. Nothing prevents it; it is simply not what the area tab is for.
 */
object DeviceColumns {

    /**
     * Whether the layer applies where the player is standing.
     *
     * Deliberately keyed off [DungeonLocation] rather than the paint scope: the scope resolves to
     * null whenever `dungeonRoomScope` is off, which would silently tie this feature to an
     * unrelated setting.
     *
     * No phase test, on purpose. The bands are `base.y .. base.y + 37`, which is entirely inside
     * the phase-2 slab, so no rule can reach another phase's geometry; and the rule matches live
     * blocks, so during the phases when the pillars are absent there is nothing to match anyway.
     * The phase upstream computes is derived from the player's own Y, which crosses its thresholds
     * constantly during the fight - gating a full-view rebuild on that would be far worse than the
     * nothing it would save. If arena-wide culling ever shows up in a profile, add the gate through
     * the same edge in [com.maxisch.dungeon.RoomTracker], and give it hysteresis.
     */
    fun shouldApply(): Boolean =
        ApSettings.deviceEnabled &&
            DeviceColumnData.loaded &&
            DungeonLocation.floorNumber == 7 &&
            DungeonLocation.inBoss

    internal fun activeColumns(): ColumnRules = if (!shouldApply()) ColumnRules.EMPTY else cached()

    /**
     * Drops the built rules. Needed whenever a rule, the seed mode or a palette one of them names
     * changes - not when paint changes, which is why this is cached rather than rebuilt from
     * [PaintIndexBuilder]: a build reads palette files, and the index refreshes on every stroke.
     */
    fun invalidate() {
        cache = null
    }

    /** How many rules are actually pointed at something, for the status command. */
    fun ruleCount(): Int = DeviceArray.entries.sumOf { array ->
        DeviceSource.entries.count { ApSettings.deviceRule(array, it) != null }
    }

    // ------------------------------------------------------------ lookups for the debug commands

    /** One listed column: which array it belongs to and where its band starts. */
    data class Column(val array: DeviceArray, val base: DevicePoint) {
        val minY: Int get() = base.y
        val maxY: Int get() = base.y + DeviceColumnData.COLUMN_HEIGHT
    }

    fun columnAt(x: Int, z: Int): Column? = columns().firstOrNull { it.base.x == x && it.base.z == z }

    fun nearestColumn(x: Int, z: Int): Column? = columns().minByOrNull {
        val dx = (it.base.x - x).toLong()
        val dz = (it.base.z - z).toLong()
        dx * dx + dz * dz
    }

    fun sourceFor(block: Block): DeviceSource? = DeviceSource.entries.firstOrNull { it.block == block }

    /**
     * The bounding box of every bundled column, bands included - null when there is no data to
     * bound. Lets a rule change dirty just the arena's footprint instead of the whole loaded view;
     * the bundled geometry never changes at runtime, so this is computed once and kept.
     */
    fun bounds(): Pair<BlockPos, BlockPos>? {
        boundsCache?.let { return it }
        if (!DeviceColumnData.loaded) return null

        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var maxZ = Int.MIN_VALUE
        var any = false

        for (point in columns().map { it.base }) {
            any = true
            if (point.x < minX) minX = point.x
            if (point.z < minZ) minZ = point.z
            if (point.x > maxX) maxX = point.x
            if (point.z > maxZ) maxZ = point.z
            if (point.y < minY) minY = point.y
            val top = point.y + DeviceColumnData.COLUMN_HEIGHT
            if (top > maxY) maxY = top
        }
        if (!any) return null

        val result = BlockPos(minX, minY, minZ) to BlockPos(maxX, maxY, maxZ)
        boundsCache = result
        return result
    }

    private var boundsCache: Pair<BlockPos, BlockPos>? = null

    /**
     * What a rule would paint at one position, resolved the same way the built layer resolves it.
     * Answers the probe command whether or not the layer is currently live, which is the point of
     * having a probe.
     */
    fun donorFor(array: DeviceArray, source: DeviceSource, x: Int, y: Int, z: Int): Block? =
        columnTarget(array, source)?.resolve(x, y, z)

    private fun columns(): Sequence<Column> = DeviceArray.entries.asSequence().flatMap { array ->
        DeviceColumnData.points(array.jsonKey).asSequence().map { Column(array, it) }
    }

    private var cache: ColumnRules? = null

    private fun cached(): ColumnRules = cache ?: build().also { cache = it }

    private fun build(): ColumnRules {
        val byColumn = Long2ObjectOpenHashMap<ColumnBand>()
        val sources = ReferenceOpenHashSet<Block>()

        for (array in DeviceArray.entries) {
            val targets = Reference2ObjectOpenHashMap<Block, ColumnTarget>()
            for (source in DeviceSource.entries) {
                val target = columnTarget(array, source) ?: continue
                targets.put(source.block, target)
                // A source only guards the hot path if something actually paints it, so turning
                // every rule off empties the set and the layer stops costing anything at all.
                sources.add(source.block)
            }
            if (targets.isEmpty()) continue

            for (point in DeviceColumnData.points(array.jsonKey)) {
                val key = ColumnRules.key(point.x, point.z)
                val existing = byColumn.get(key)
                if (existing != null) {
                    LOGGER.warn("Two device columns share x {} z {}; both will be tried", point.x, point.z)
                }
                byColumn.put(
                    key,
                    ColumnBand(
                        point.y,
                        point.y + DeviceColumnData.COLUMN_HEIGHT,
                        targets,
                        existing,
                    ),
                )
            }
        }

        return ColumnRules(byColumn, sources)
    }

    /**
     * A rule naming a palette that has gone missing falls back to the array's default colour
     * rather than to painting nothing: a pillar left as plain diorite is indistinguishable from
     * the whole feature being broken, whereas the default colour still identifies the array.
     */
    private fun columnTarget(array: DeviceArray, source: DeviceSource): ColumnTarget? =
        when (val target = ApSettings.deviceRule(array, source)) {
            null -> null
            is AreaTarget.Donor -> ColumnTarget.Donor(target.block)
            is AreaTarget.Palette -> {
                val picker = PresetStores.palettes.read(target.name).picker()
                if (picker == null) {
                    LOGGER.warn(
                        "Device rule {}.{} names palette '{}', which is empty or missing; using its default colour",
                        array.key,
                        source.key,
                        target.name,
                    )
                    ColumnTarget.Donor(array.defaultDonor)
                } else {
                    // Always one roll per column, so a whole pillar reads as one colour and keeps
                    // it as the pillar moves - the per-block alternative (speckled, no per-pillar
                    // identity) is no longer offered as a setting.
                    ColumnTarget.Rolled(picker, perColumn = true)
                }
            }
        }
}
