package com.maxisch.paint.rule

import com.maxisch.dungeon.detect.DoorKind
import com.maxisch.dungeon.detect.DoorScanner
import com.maxisch.dungeon.detect.DungeonLocation
import com.maxisch.paint.settings.ApSettings
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block

/**
 * Repaints the start/wither/blood doors while they are closed, wherever this run's layout put
 * them - the dynamic-position counterpart to [BossZones]' fixed boss-room zones. Matched on the
 * live block the same way a boss zone is, so a door still repaints right up until the moment it
 * actually opens and its blocks stop existing.
 */
object DoorZones {

    fun shouldApply(): Boolean = DungeonLocation.inDungeon && !DungeonLocation.inBoss

    internal fun activeZones(): ZoneRules = if (!shouldApply()) ZoneRules.EMPTY else cached()

    /** Whether any door currently has a sub-255 opacity configured - an opacity-only door (no
     *  donor bound) never enters [activeZones]/[ZoneRules], since that layer only tracks donors,
     *  so callers that need to know "is any block rendering translucent because of a door" (the
     *  culling fix, chiefly) have to ask this separately. */
    fun opacityActive(): Boolean {
        if (!shouldApply()) return false
        for (kind in DoorKind.entries) if (ApSettings.doorOpacity(kind) < 255) return true
        return false
    }

    /** Dropped whenever a door cell resolves to a kind, or a donor/opacity binding changes. */
    fun invalidate() {
        cache = null
    }

    /**
     * The configured alpha (0-255) for whichever door, if any, [pos] falls inside - null everywhere
     * else, including a door left at the default 255 (fully opaque; no reason to force the
     * translucent layer for that). Independent of whether that door also has a donor bound: opacity
     * alone still applies to the block's own real texture, same as it would to a donor's.
     *
     * [block] must match the door kind's own material, exactly like [ZoneRules.paintAt] already
     * requires for a donor. Membership is checked against the door's exact flood-filled blocks
     * ([DoorScanner.containsAt]), not its bounding box - a same-material block that merely sits
     * inside that box (a frame/trim built from the same block, say) without being part of the
     * connected door blob itself must not turn translucent too.
     */
    fun opacityAt(pos: BlockPos, block: Block): Int? {
        if (!shouldApply()) return null
        for (kind in DoorKind.entries) {
            if (block != kind.source) continue
            val opacity = ApSettings.doorOpacity(kind)
            if (opacity >= 255) continue
            if (DoorScanner.containsAt(kind, pos)) return opacity
        }
        return null
    }

    private var cache: ZoneRules? = null

    private fun cached(): ZoneRules = cache ?: build().also { cache = it }

    private fun build(): ZoneRules {
        val sources = ReferenceOpenHashSet<Block>()
        val entries = ArrayList<ZoneEntry>()

        for (kind in DoorKind.entries) {
            val donor = ApSettings.doorDonor(kind) ?: continue
            val boxes = DoorScanner.boundsFor(kind)
            if (boxes.isEmpty()) continue

            val bounds = boxes.map { (min, max) -> ZoneBounds(min.x, min.y, min.z, max.x, max.y, max.z) }
            entries.add(ZoneEntry(bounds, mapOf(ZoneKey(kind.source, null) to ColumnTarget.Donor(donor))))
            sources.add(kind.source)
        }

        return ZoneRules(entries, sources)
    }
}
