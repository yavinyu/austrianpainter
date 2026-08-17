package com.maxisch.dungeon.detect

import com.maxisch.dungeon.room.DungeonGrid
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/** What a closed dungeon door is built from, once a door cell resolves to one. */
internal enum class DoorKind(val source: Block) {
    ENTRANCE(Blocks.INFESTED_CHISELED_STONE_BRICKS),
    WITHER(Blocks.COAL_BLOCK),
    BLOOD(Blocks.RED_TERRACOTTA),
}

/**
 * Finds which door cells ([RoomScanner.doorCells]) are which door, by matching the live block
 * against each [DoorKind]'s known material. A door moves with the run's room layout the same way
 * a room does, so this scans instead of using fixed coordinates - unlike a boss zone
 * ([com.maxisch.paint.rule.BossZones]), which sits at the same world position every run.
 */
internal object DoorScanner {

    private const val RESCAN_DELAY_MS = 250L

    /** Half-width of the box probed around a door cell's centre - a little wider than the 16-block
     *  cell spacing itself, so a door sitting slightly off-centre within its cell is still caught. */
    private const val PROBE_HALF = 8

    /** How far below the cell's roof height to probe. The door is built flush with the corridor
     *  ceiling it seals, so this reuses the same roof read that already classified the cell as a
     *  doorway rather than guessing a new offset. */
    private const val PROBE_DEPTH = 4

    /** A closed door is 3x4x3 = 36 blocks. Capped a little above that so a cluster never grows past
     *  roughly the door's own footprint even when it is physically touching more of the identical
     *  material - a frame or trim built from the same block, which is otherwise a legitimately
     *  connected (and so, without a cap, unbounded) blob. */
    private const val MAX_CLUSTER = 48

    /** One found door instance: its bounding box (for the donor zone, which - like every other
     *  zone - paints by box) and the exact set of blocks the flood fill actually walked (for
     *  opacity, which must never bleed onto a same-material block merely inside that box but not
     *  part of the connected door itself). */
    internal class Cluster(val bounds: Pair<BlockPos, BlockPos>, val positions: LongOpenHashSet)

    private val found = LinkedHashMap<DoorKind, MutableList<Cluster>>()
    private val resolvedCells = HashSet<Long>()

    var version: Int = 0
        private set

    private var lastScan = 0L

    fun reset() {
        found.clear()
        resolvedCells.clear()
        version++
        lastScan = 0L
    }

    fun tick() {
        val now = System.currentTimeMillis()
        if (now - lastScan < RESCAN_DELAY_MS) return
        lastScan = now

        for (cell in RoomScanner.doorCells()) {
            val key = BlockPos(cell.x, 0, cell.z).asLong()
            if (key in resolvedCells) continue
            if (probe(cell.x, cell.z)) resolvedCells.add(key)
        }
    }

    fun boundsFor(kind: DoorKind): List<Pair<BlockPos, BlockPos>> = found[kind].orEmpty().map { it.bounds }

    /** Exact membership, not the bounding box - a same-material block that merely sits inside a
     *  door's box without being part of the connected blob it was flood-filled from does not count. */
    fun containsAt(kind: DoorKind, pos: BlockPos): Boolean {
        val clusters = found[kind] ?: return false
        val long = pos.asLong()
        return clusters.any { long in it.positions }
    }

    /** Returns true once the cell has been fully probed - whether or not a door was found there,
     *  so an empty corridor gap is not re-scanned forever. */
    private fun probe(x: Int, z: Int): Boolean {
        val minX = x - PROBE_HALF
        val maxX = x + PROBE_HALF
        val minZ = z - PROBE_HALF
        val maxZ = z + PROBE_HALF
        if (!WorldProbe.isChunkLoaded(minX, minZ) || !WorldProbe.isChunkLoaded(maxX, maxZ)) return false

        val roof = WorldProbe.highestY(x, z).takeUnless { it <= 0 } ?: return false
        val minY = roof - PROBE_DEPTH

        val matches = HashMap<DoorKind, MutableList<BlockPos>>()
        val cursor = BlockPos.MutableBlockPos()
        for (px in minX..maxX) {
            for (py in minY..roof) {
                for (pz in minZ..maxZ) {
                    cursor.set(px, py, pz)
                    val block = WorldProbe.blockAt(cursor)
                    val kind = DoorKind.entries.firstOrNull { it.source == block } ?: continue
                    matches.getOrPut(kind) { mutableListOf() }.add(cursor.immutable())
                }
            }
        }

        if (matches.isEmpty()) return true // fully probed, genuinely not a door

        // A stray decorative block of the same material elsewhere in the probe box must not stretch
        // the recorded box out to it - only the door's own solid, fully-connected blob counts.
        for ((kind, positions) in matches) {
            val cluster = largestCluster(positions) ?: continue
            val set = LongOpenHashSet(cluster.size).apply { cluster.forEach { add(it.asLong()) } }
            found.getOrPut(kind) { mutableListOf() }.add(Cluster(boundsOf(cluster), set))
            version++
        }
        return true
    }

    /** The biggest set of face-adjacent matches - a real door is one solid 36-block blob, while an
     *  unrelated match elsewhere in the probe box forms its own small, disconnected cluster. */
    private fun largestCluster(positions: List<BlockPos>): List<BlockPos>? {
        if (positions.isEmpty()) return null
        val set = positions.mapTo(HashSet()) { it.asLong() }
        val visited = HashSet<Long>()
        var best: List<BlockPos>? = null

        for (start in positions) {
            if (start.asLong() in visited) continue
            val component = mutableListOf<BlockPos>()
            val queue = ArrayDeque<BlockPos>().apply { add(start) }
            visited.add(start.asLong())
            while (queue.isNotEmpty() && component.size < MAX_CLUSTER) {
                val p = queue.removeFirst()
                component.add(p)
                for (dir in Direction.entries) {
                    val n = p.relative(dir)
                    if (n.asLong() in set && visited.add(n.asLong())) queue.add(n)
                }
            }
            if (best == null || component.size > best!!.size) best = component
        }
        return best
    }

    private fun boundsOf(cluster: List<BlockPos>): Pair<BlockPos, BlockPos> {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var maxZ = Int.MIN_VALUE
        for (p in cluster) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.z < minZ) minZ = p.z
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
            if (p.z > maxZ) maxZ = p.z
        }
        return BlockPos(minX, minY, minZ) to BlockPos(maxX, maxY, maxZ)
    }
}
