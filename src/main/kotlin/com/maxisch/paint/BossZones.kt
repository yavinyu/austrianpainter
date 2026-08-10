package com.maxisch.paint

import com.maxisch.dungeon.DungeonLocation
import com.maxisch.paint.ApLog.LOGGER
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet
import it.unimi.dsi.fastutil.objects.ReferenceSet
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties

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

/** The five boss-room zones a player can aim a donor/palette at. */
enum class BossZone(val key: String, internal val bounds: ZoneBounds) {
    PILLARS("pillars", ZoneBounds(34, 225, 72, 112, 226, 74)),
    S1("s1", ZoneBounds(111, 120, 92, 111, 123, 95)),
    S2("s2", ZoneBounds(58, 133, 143, 62, 136, 143)),
    S3("s3", ZoneBounds(-3, 120, 75, -3, 124, 79)),
    S4("s4", ZoneBounds(64, 126, 50, 68, 130, 50)),
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
        ZoneSourceRule(BossZone.S1, Blocks.SEA_LANTERN, default = AreaTarget.Donor(Blocks.OBSIDIAN)),
        ZoneSourceRule(BossZone.S1, Blocks.OBSIDIAN, default = AreaTarget.Donor(Blocks.SEA_LANTERN)),
        ZoneSourceRule(BossZone.S2, Blocks.REDSTONE_LAMP, lit = true),
        ZoneSourceRule(BossZone.S2, Blocks.REDSTONE_LAMP, lit = false),
        ZoneSourceRule(BossZone.S3, Blocks.SEA_LANTERN, default = AreaTarget.Donor(Blocks.BLUE_TERRACOTTA)),
        ZoneSourceRule(BossZone.S3, Blocks.BLUE_TERRACOTTA, default = AreaTarget.Donor(Blocks.SEA_LANTERN)),
        ZoneSourceRule(BossZone.S4, Blocks.EMERALD_BLOCK, default = AreaTarget.Donor(Blocks.BLUE_TERRACOTTA)),
        ZoneSourceRule(BossZone.S4, Blocks.BLUE_TERRACOTTA, default = AreaTarget.Donor(Blocks.EMERALD_BLOCK)),
    )

    fun shouldApply(): Boolean =
        ApSettings.zonesEnabled && DungeonLocation.floorNumber == 7 && DungeonLocation.inBoss

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
            val (min, max) = zone.bounds.corners()
            if (min.x < minX) minX = min.x
            if (min.y < minY) minY = min.y
            if (min.z < minZ) minZ = min.z
            if (max.x > maxX) maxX = max.x
            if (max.y > maxY) maxY = max.y
            if (max.z > maxZ) maxZ = max.z
        }

        return BlockPos(minX, minY, minZ) to BlockPos(maxX, maxY, maxZ)
    }

    private var cache: ZoneRules? = null

    private fun cached(): ZoneRules = cache ?: build().also { cache = it }

    private fun build(): ZoneRules {
        val sources = ReferenceOpenHashSet<Block>()
        val byZone = LinkedHashMap<BossZone, MutableMap<ZoneKey, ColumnTarget>>()

        for (rule in RULES) {
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

/** One zone's bounds plus what each of its (block, lit) sources becomes. */
internal class ZoneEntry(val bounds: ZoneBounds, val targets: Map<ZoneKey, ColumnTarget>)

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
            if (!entry.bounds.contains(x, y, z)) continue
            val target = entry.targets[key] ?: continue
            return target.resolve(x, y, z)
        }
        return null
    }

    companion object {
        val EMPTY = ZoneRules(emptyList(), ReferenceOpenHashSet())
    }
}
