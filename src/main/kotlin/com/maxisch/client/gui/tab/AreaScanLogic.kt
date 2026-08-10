package com.maxisch.client.gui.tab

import com.maxisch.client.KeyHints
import com.maxisch.client.gui.ConfirmAction
import com.maxisch.client.gui.PainterScreen
import com.maxisch.paint.PaintStorage
import com.maxisch.paint.PalettePreset
import com.maxisch.paint.PresetStores
import com.maxisch.paint.session.AreaScan
import com.maxisch.paint.session.PaintArea
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Block

/**
 * Box-scan and apply logic for the area tab: what is inside the selected box, and the four ways to
 * repaint it. Split out from [AreaTab] so the scan/paint rules can be read without the widget and
 * layout code around them. Holds no widgets itself - callers still own rescanning/refreshing the UI
 * after calling into this class.
 */
class AreaScanLogic(private val screen: PainterScreen) {

    sealed interface Row {
        /** Synthetic first row: every non-air block in the box. */
        data class All(val count: Int) : Row
        data class Type(val block: Block, val count: Int) : Row
    }

    /** Empty when there is no level, the box is incomplete, or the box is too big to scan. */
    fun scan(): List<Row> {
        val level = Minecraft.getInstance().level
        val rows = if (level == null || !PaintArea.complete || tooBig()) {
            emptyList()
        } else {
            val histogram = AreaScan.histogram(level)
            buildList {
                add(Row.All(histogram.values.sum()))
                histogram.forEach { (block, count) -> add(Row.Type(block, count)) }
            }
        }

        // Drop a source that is no longer in the box so the button state cannot lie.
        if (rows.none { it is Row.Type && it.block == PaintArea.source }) PaintArea.source = null
        return rows
    }

    fun tooBig(): Boolean = PaintArea.volume() > PaintArea.MAX_VOLUME

    fun palette(): PalettePreset = PresetStores.palettes.active

    /** One donor for the whole selection. */
    fun replace() {
        val donor = PaintArea.donor ?: return
        val positions = targetPositions() ?: return

        PaintArea.lastApplied = positions
        val painted = PaintStorage.paintPositions(positions, donor)
        screen.status(
            Component.translatable("austrianpainter.area.replaced_donor", painted, sourceName(), donor.name),
        )
    }

    /** A weighted draw from the active palette, one roll per position. */
    fun replaceRandom() {
        val positions = targetPositions() ?: return

        PaintArea.lastApplied = positions
        val painted = draw(positions)
        screen.status(
            Component.translatable(
                "austrianpainter.area.replaced",
                painted,
                sourceName(),
                PresetStores.palettes.activeName,
            ),
        )
    }

    /** Redraws the previous apply. The same positions, a fresh roll of the same palette. */
    fun reroll() {
        val positions = PaintArea.lastApplied
        if (positions.isEmpty()) return

        val painted = draw(positions)
        screen.status(Component.translatable("austrianpainter.area.rerolled", painted))
    }

    fun clearPainted() {
        if (!PaintArea.complete || tooBig()) return

        ConfirmAction.ask(
            screen,
            Component.translatable("austrianpainter.area.clear_painted.confirm.title"),
            Component.translatable("austrianpainter.area.clear_painted.confirm.message", PaintArea.volume()),
        ) {
            val cleared = PaintStorage.unpaintPositions(PaintArea.positions().toList())
            screen.status(Component.translatable("austrianpainter.area.cleared", cleared))
        }
    }

    private fun targetPositions(): List<BlockPos>? {
        val level = Minecraft.getInstance().level ?: return null
        if (!PaintArea.hasSource) return null

        return if (PaintArea.sourceAll) {
            AreaScan.allPositions(level)
        } else {
            AreaScan.positionsOf(level, PaintArea.source ?: return null)
        }
    }

    private fun draw(positions: List<BlockPos>): Int {
        val picker = palette().picker() ?: return 0
        val random = RandomSource.create()
        return PaintStorage.paintPositions(positions) { picker.next(random) }
    }

    fun sizeText(): Component {
        val min = PaintArea.min()
        val max = PaintArea.max()
        if (min == null || max == null) return KeyHints.cornerHint()
        if (tooBig()) return Component.translatable("austrianpainter.area.too_big", PaintArea.MAX_VOLUME)

        return Component.translatable(
            "austrianpainter.area.size",
            max.x - min.x + 1,
            max.y - min.y + 1,
            max.z - min.z + 1,
            PaintArea.volume(),
        )
    }

    fun sourceName(): Component =
        if (PaintArea.sourceAll) {
            Component.translatable("austrianpainter.area.everything")
        } else {
            PaintArea.source?.name ?: Component.translatable("austrianpainter.area.any_source")
        }

    fun sourceText(): Component =
        if (PaintArea.hasSource) {
            Component.translatable("austrianpainter.area.source_line", sourceName())
        } else {
            Component.translatable("austrianpainter.area.no_source")
        }

    /** Both apply paths at once, so it is obvious which of the two buttons is ready. */
    fun donorText(): Component = Component.translatable(
        "austrianpainter.area.donor_line",
        PaintArea.donor?.name ?: Component.translatable("austrianpainter.area.any_source"),
        PresetStores.palettes.activeName,
        palette().size,
    )

    fun emptyText(): Component = when {
        !PaintArea.complete -> KeyHints.cornerHint()
        tooBig() -> Component.translatable("austrianpainter.area.too_big", PaintArea.MAX_VOLUME)
        else -> Component.translatable("austrianpainter.area.scan_empty")
    }
}
