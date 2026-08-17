package com.maxisch.paint.rule

import com.maxisch.dungeon.detect.DungeonLocation
import com.maxisch.paint.settings.ApLog.LOGGER
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet
import it.unimi.dsi.fastutil.objects.ReferenceSet
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import com.maxisch.paint.settings.ApSettings
import com.maxisch.paint.preset.PresetStores

/**
 * Fixed F7/M7 Sadan boss-room zones, each repainting a handful of source blocks - matched on the
 * live block (and, for the lamp zone, its `lit` state) rather than on a coordinate, so the pillars
 * zone keeps working while its blocks move, exactly like [DeviceColumns].
 *
 * Inclusive on every axis, unlike [net.minecraft.world.phys.AABB.contains] (max-exclusive) - every
 * zone here is pinned flat on one axis (min == max), which an exclusive test would always miss.
 */
internal class ZoneBounds(
    private val minX: Int,
    private val minY: Int,
    private val minZ: Int,
    private val maxX: Int,
    private val maxY: Int,
    private val maxZ: Int,
) {
    fun contains(x: Int, y: Int, z: Int): Boolean = x in minX..maxX && y in minY..maxY && z in minZ..maxZ

    fun corners(): Pair<BlockPos, BlockPos> = BlockPos(minX, minY, minZ) to BlockPos(maxX, maxY, maxZ)
}

/** The boss-room zones a player can aim a donor/palette at. Most are one box; [BossZone.CRUSHER]
 *  and [BossZone.CRUSHER_P1] are several, one per crusher's full travel range, since a single
 *  moving block can visit any of them - matched live the same way one zone's own box already
 *  keeps up with movement inside it.
 *
 *  [p1] marks which phase-column ([DungeonTab]'s P1 "Conveyer") a zone's rules live under; every
 *  other zone is P3 "Devices". It exists because that grouping is no longer just "is this
 *  [PILLARS]" now that a second zone ([CRUSHER_P1]) also belongs to P1 while being otherwise
 *  unrelated to it - a different in-game location with its own donor rule and reset scope. */
enum class BossZone(val key: String, val p1: Boolean, internal val bounds: List<ZoneBounds>) {
    PILLARS("pillars", true, listOf(ZoneBounds(34, 225, 72, 112, 226, 74))),
    S1("s1", false, listOf(ZoneBounds(111, 120, 92, 111, 123, 95))),
    S2("s2", false, listOf(ZoneBounds(58, 133, 143, 62, 136, 143))),
    S3("s3", false, listOf(ZoneBounds(-3, 120, 75, -3, 124, 79))),
    S4("s4", false, listOf(ZoneBounds(64, 126, 50, 68, 130, 50))),
    CRUSHER(
        "crusher",
        false,
        listOf(
            ZoneBounds(-3, 107, 95, 19, 108, 97),
            ZoneBounds(1, 117, 101, 4, 127, 102),
            ZoneBounds(12, 117, 101, 15, 127, 102),
            ZoneBounds(1, 117, 82, 3, 127, 84),
            ZoneBounds(13, 117, 82, 15, 127, 84),
            ZoneBounds(1, 117, 70, 3, 127, 72),
            ZoneBounds(13, 117, 70, 15, 127, 72),
        ),
    ),
    CRUSHER_P1(
        "crusher_p1",
        true,
        listOf(
            ZoneBounds(96, 237, 53, 103, 239, 55),
            ZoneBounds(98, 228, 82, 99, 228, 84),
            ZoneBounds(52, 227, 82, 53, 227, 84),
            ZoneBounds(39, 231, 82, 40, 231, 84),
            ZoneBounds(47, 233, 82, 48, 233, 84),
            ZoneBounds(60, 229, 82, 61, 229, 84),
            ZoneBounds(49, 229, 80, 51, 230, 81),
            ZoneBounds(95, 233, 79, 97, 233, 81),
            ZoneBounds(106, 230, 82, 107, 230, 84),
        ),
    ),
}

/**
 * One (zone, source block) row. [lit] is null for every source except the redstone lamp rows in
 * [BossZone.S2], where it splits the single `Block` into two independent rows.
 */
data class ZoneSourceRule(
    val zone: BossZone,
    val block: Block,
    val lit: Boolean? = null,
    val default: AreaTarget? = null,
)

/** The lookup key inside one zone's rule map - lets the same [Block] (redstone lamp) occupy two
 *  independent rows within one zone. */
internal data class ZoneKey(val block: Block, val lit: Boolean?)

object BossZones {

    val RULES: List<ZoneSourceRule> = listOf(
        ZoneSourceRule(BossZone.PILLARS, Blocks.COAL_BLOCK),
        ZoneSourceRule(BossZone.PILLARS, Blocks.PLAYER_HEAD),
        ZoneSourceRule(BossZone.PILLARS, Blocks.PLAYER_WALL_HEAD),
        ZoneSourceRule(BossZone.S1, Blocks.SEA_LANTERN),
        ZoneSourceRule(BossZone.S1, Blocks.OBSIDIAN),
        ZoneSourceRule(BossZone.S2, Blocks.REDSTONE_LAMP, lit = true),
        ZoneSourceRule(BossZone.S2, Blocks.REDSTONE_LAMP, lit = false),
        ZoneSourceRule(BossZone.S3, Blocks.SEA_LANTERN),
        ZoneSourceRule(BossZone.S3, Blocks.BLUE_TERRACOTTA),
        ZoneSourceRule(BossZone.S4, Blocks.EMERALD_BLOCK),
        ZoneSourceRule(BossZone.S4, Blocks.BLUE_TERRACOTTA),
        ZoneSourceRule(BossZone.CRUSHER, Blocks.POLISHED_GRANITE),
        ZoneSourceRule(BossZone.CRUSHER_P1, Blocks.POLISHED_GRANITE),
    )

    /** True while either boss phase - P1 (conveyer) or P3 (devices) - is live. */
    fun shouldApply(): Boolean =
        (ApSettings.conveyorEnabled || ApSettings.zonesEnabled) &&
            DungeonLocation.floorNumber == 7 &&
            DungeonLocation.inBoss

    internal fun activeZones(): ZoneRules = if (!shouldApply()) ZoneRules.EMPTY else cached()

    /** Drops the built rules; same contract as [DeviceColumns.invalidate]. */
    fun invalidate() {
        cache = null
    }

    /** How many rules are actually pointed at something, for the status command. */
    fun ruleCount(): Int = RULES.count { ApSettings.zoneRule(it) != null }

    /**
     * The bounding box of every zone. Always non-null - the zone list is fixed and never empty -
     * so a rule change only ever needs to dirty this footprint, not the whole loaded view.
     */
    fun bounds(): Pair<BlockPos, BlockPos> {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var maxZ = Int.MIN_VALUE

        for (zone in BossZone.entries) {
            for (box in zone.bounds) {
                val (min, max) = box.corners()
                if (min.x < minX) minX = min.x
                if (min.y < minY) minY = min.y
                if (min.z < minZ) minZ = min.z
                if (max.x > maxX) maxX = max.x
                if (max.y > maxY) maxY = max.y
                if (max.z > maxZ) maxZ = max.z
            }
        }

        return BlockPos(minX, minY, minZ) to BlockPos(maxX, maxY, maxZ)
    }

    private var cache: ZoneRules? = null

    private fun cached(): ZoneRules = cache ?: build().also { cache = it }

    private fun build(): ZoneRules {
        val sources = ReferenceOpenHashSet<Block>()
        val byZone = LinkedHashMap<BossZone, MutableMap<ZoneKey, ColumnTarget>>()

        for (rule in RULES) {
            // P1 (BossZone.p1 zones) and P3 (everything else) are independent boss phases with
            // their own Live toggle - a rule from the off phase is skipped exactly as if it had no
            // target set.
            val phaseEnabled = if (rule.zone.p1) ApSettings.conveyorEnabled else ApSettings.zonesEnabled
            if (!phaseEnabled) continue
            val target = columnTarget(rule) ?: continue
            byZone.getOrPut(rule.zone) { LinkedHashMap() }[ZoneKey(rule.block, rule.lit)] = target
            sources.add(rule.block)
        }

        val entries = BossZone.entries.mapNotNull { zone -> byZone[zone]?.let { ZoneEntry(zone.bounds, it) } }
        return ZoneRules(entries, sources)
    }

    /**
     * A rule naming a palette that has gone missing falls back to the rule's own default donor (if
     * any) rather than to painting nothing - same "missing palette still shows something" philosophy
     * as [DeviceColumns.columnTarget], adapted since boss zones have no per-array default colour.
     */
    private fun columnTarget(rule: ZoneSourceRule): ColumnTarget? =
        when (val target = ApSettings.zoneRule(rule)) {
            null -> null
            is AreaTarget.Donor -> ColumnTarget.Donor(target.block)
            is AreaTarget.Palette -> {
                val picker = PresetStores.palettes.read(target.name).picker()
                if (picker != null) {
                    ColumnTarget.Rolled(picker, perColumn = false)
                } else {
                    LOGGER.warn(
                        "Boss zone rule {}.{} names palette '{}', which is empty or missing",
                        rule.zone.key,
                        rule.block,
                        target.name,
                    )
                    (rule.default as? AreaTarget.Donor)?.let { ColumnTarget.Donor(it.block) }
                }
            }
        }
}

/** One zone's bounds (one box for most zones, seven for [BossZone.CRUSHER]) plus what each of its
 *  (block, lit) sources becomes. */
internal class ZoneEntry(val bounds: List<ZoneBounds>, val targets: Map<ZoneKey, ColumnTarget>)

/** The whole zone layer of one [PaintIndex] snapshot: the zones, plus the guard that keeps them
 *  free everywhere else. */
internal class ZoneRules(
    private val entries: List<ZoneEntry>,
    val sources: ReferenceSet<Block>,
) {
    val empty: Boolean = entries.isEmpty() || sources.isEmpty()

    fun paintAt(x: Int, y: Int, z: Int, state: BlockState): Block? {
        val block = state.block
        val lit = if (state.hasProperty(BlockStateProperties.LIT)) state.getValue(BlockStateProperties.LIT) else null
        val key = ZoneKey(block, lit)

        for (entry in entries) {
            if (entry.bounds.none { it.contains(x, y, z) }) continue
            val target = entry.targets[key] ?: continue
            return target.resolve(x, y, z)
        }
        return null
    }

    /** Combines two independent zone layers - boss zones and door zones - into one, so [PaintIndex]
     *  only ever has to consult a single [ZoneRules] regardless of how many layers feed it. */
    fun merge(other: ZoneRules): ZoneRules {
        if (empty) return other
        if (other.empty) return this
        return ZoneRules(entries + other.entries, ReferenceOpenHashSet(sources + other.sources))
    }

    companion object {
        val EMPTY = ZoneRules(emptyList(), ReferenceOpenHashSet())
    }
}
