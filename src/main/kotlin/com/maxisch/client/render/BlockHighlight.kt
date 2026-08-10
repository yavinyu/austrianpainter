package com.maxisch.client.render

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.world.phys.AABB

/**
 * Outlines individual blocks in the world, on the same gizmo path [AreaHighlight] draws the area box
 * with: emitted once per tick and re-submitted every frame.
 *
 * Two independent layers, because both want to be visible at once and neither should clobber the
 * other - [preview] is what an area apply would touch, [painted] is what is already painted.
 *
 * One cuboid per block is not free, so every layer is capped at [MAX_BOXES]. Going over does not
 * silently draw a subset: [truncated] is true and the HUD says so, because a highlight that quietly
 * stops halfway is worse than no highlight at all.
 */
object BlockHighlight {

    /** Roughly what can be drawn without the frame time moving. */
    const val MAX_BOXES = 4_000

    private const val STROKE_WIDTH = 1.5f

    private var preview: List<BlockPos> = emptyList()
    private var previewColor = 0

    private var painted: List<BlockPos> = emptyList()
    private var paintedColor = 0

    /** True while either layer had to be refused for being too large. */
    var truncated: Boolean = false
        private set

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client -> emit(client) }
    }

    private var previewRefused = false
    private var paintedRefused = false

    val hasPreview: Boolean get() = preview.isNotEmpty() || previewRefused

    fun showPreview(positions: List<BlockPos>, color: Int) {
        previewColor = color
        previewRefused = positions.size > MAX_BOXES
        preview = if (previewRefused) emptyList() else positions
        refreshTruncated()
    }

    fun clearPreview() {
        preview = emptyList()
        previewRefused = false
        refreshTruncated()
    }

    fun showPainted(positions: List<BlockPos>, color: Int) {
        paintedColor = color
        paintedRefused = positions.size > MAX_BOXES
        painted = if (paintedRefused) emptyList() else positions
        refreshTruncated()
    }

    fun clearPainted() {
        painted = emptyList()
        paintedRefused = false
        refreshTruncated()
    }

    fun clear() {
        clearPreview()
        clearPainted()
    }

    private fun refreshTruncated() {
        truncated = previewRefused || paintedRefused
    }

    private fun emit(client: Minecraft) {
        if (client.level == null) return
        if (preview.isEmpty() && painted.isEmpty()) return

        client.collectPerTickGizmos().use {
            draw(painted, paintedColor)
            draw(preview, previewColor)
        }
    }

    private fun draw(positions: List<BlockPos>, color: Int) {
        if (positions.isEmpty()) return
        // Outline only - a fill on thousands of overlapping boxes is unreadable, and the point is
        // to see the blocks underneath.
        val style = GizmoStyle.strokeAndFill(color, STROKE_WIDTH, 0)
        for (pos in positions) {
            Gizmos.cuboid(AABB.encapsulatingFullBlocks(pos, pos), style)
        }
    }
}
