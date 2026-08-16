package com.maxisch.client.gui.tab

import com.maxisch.client.keybind.KeyHints
import com.maxisch.client.gui.ConfirmAction
import com.maxisch.client.gui.screen.PainterScreen
import com.maxisch.client.render.overlay.BlockHighlight
import com.maxisch.client.render.overlay.PaintedOverlay
import com.maxisch.paint.settings.ApSettings
import com.maxisch.paint.rule.AreaSelector
import com.maxisch.paint.rule.AreaTarget
import com.maxisch.paint.rule.PaintFilter
import com.maxisch.paint.rule.PaintRules
import com.maxisch.paint.PaintStorage
import com.maxisch.paint.preset.PalettePreset
import com.maxisch.paint.preset.PresetStores
import com.maxisch.paint.preset.RulesetPreset
import com.maxisch.paint.rule.displayName
import com.maxisch.paint.session.AreaScan
import com.maxisch.paint.session.PaintArea
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Block

/**
 * Box-scan and apply logic for the area tab: what is inside the selected box, the rules aimed at it,
 * and the ways to repaint it. Split out from [AreaTab] so the scan/paint rules can be read without
 * the widget and layout code around them. Holds no widgets itself - callers still own rescanning and
 * refreshing the UI after calling into this class.
 *
 * The selection ([PaintArea.selected]) is only an editing cursor. What an apply actually paints is
 * [PaintArea.rules], which is also exactly what a ruleset saves - so the arrows the player sees in
 * the list are the whole story.
 */
class AreaScanLogic(private val screen: PainterScreen) {

    /** One line of the scan list: what it selects, and how many blocks that is. */
    data class Row(val selector: AreaSelector, val count: Int)

    /** Empty when there is no level, the box is incomplete, or the box is too big to scan. */
    fun scan(): List<Row> {
        val level = Minecraft.getInstance().level
        if (level == null || !PaintArea.complete || tooBig()) {
            PaintArea.selected.clear()
            return emptyList()
        }

        val result = AreaScan.scan(level)
        val rows = buildList {
            add(Row(AreaSelector.Everything, result.total))
            add(Row(AreaSelector.Unpainted, result.unpainted))
            result.entries.forEach { entry ->
                // One row per paint state, so what a block currently renders as is visible and can
                // be aimed somewhere of its own.
                val paint = entry.paintedAs?.let { PaintFilter.PaintedAs(it) } ?: PaintFilter.Unpainted
                add(Row(AreaSelector.Type(entry.block, paint), entry.count))
            }
        }

        // Drop highlights for types that left the box, so the list cannot show a selection that has
        // no row. Rules are deliberately kept: a loaded ruleset may name blocks this box has none
        // of, and quietly gutting it when the box moves would be worse than a rule matching nothing.
        val present = rows.mapTo(HashSet()) { it.selector }
        PaintArea.selected.retainAll(present)
        return rows
    }

    fun tooBig(): Boolean = PaintArea.volume() > PaintArea.MAX_VOLUME

    fun palette(): PalettePreset = PresetStores.palettes.active

    fun ruleset(): RulesetPreset = PresetStores.rulesets.active

    // ------------------------------------------------------------------ building the rules

    /** Points everything currently highlighted at [target]. */
    fun assign(target: AreaTarget) {
        if (PaintArea.selected.isEmpty()) return
        PaintArea.selected.forEach { PaintArea.rules[it] = target }
    }

    fun assignDonor(block: Block) = assign(AreaTarget.Donor(block))

    fun assignPalette() = assign(AreaTarget.Palette(PresetStores.palettes.activeName))

    fun clearRules() {
        PaintArea.rules.clear()
        screen.status(Component.translatable("austrianpainter.area.mapping_cleared"))
    }

    // ------------------------------------------------------------------ applying

    /**
     * Outlines what [replace] would touch, without touching it. Runs exactly the same grouping call,
     * so what is shown cannot drift from what would be painted.
     */
    fun preview() {
        val level = Minecraft.getInstance().level ?: return
        if (!PaintArea.complete || tooBig() || PaintArea.rules.isEmpty()) return

        val positions = AreaScan.positionsFor(level, PaintArea.rules.keys).values.flatten()
        BlockHighlight.showPreview(positions, ApSettings.areaPreviewColor)

        screen.status(
            if (positions.size > BlockHighlight.MAX_BOXES) {
                Component.translatable("austrianpainter.area.preview_capped", positions.size, BlockHighlight.MAX_BOXES)
            } else {
                Component.translatable("austrianpainter.area.previewed", positions.size)
            },
        )
    }

    fun clearPreview() = BlockHighlight.clearPreview()

    /** Every rule at once, as a single undoable change. */
    fun replace() {
        val level = Minecraft.getInstance().level ?: return
        if (!PaintArea.complete || tooBig() || PaintArea.rules.isEmpty()) return

        val grouped = AreaScan.positionsFor(level, PaintArea.rules.keys)
        val random = RandomSource.create()
        val pickers = HashMap<String, PalettePreset.Picker?>()

        val groups = ArrayList<PaintRules.Group>(grouped.size)
        val rerollable = LinkedHashMap<String, List<BlockPos>>()

        for ((selector, positions) in grouped) {
            if (positions.isEmpty()) continue
            when (val target = PaintArea.rules[selector]) {
                is AreaTarget.Donor -> groups.add(PaintRules.Group(positions) { target.block })

                is AreaTarget.Palette -> {
                    val picker = pickers.getOrPut(target.name) { paletteNamed(target.name).picker() }
                        ?: continue
                    groups.add(PaintRules.Group(positions) { picker.next(random) })
                    rerollable.merge(target.name, positions) { a, b -> a + b }
                }

                null -> Unit
            }
        }

        val painted = PaintStorage.paintGroups(groups)
        if (painted == 0) {
            screen.status(Component.translatable("austrianpainter.area.matched_nothing"))
            return
        }

        // The preview described the world before this apply; it is a lie the moment it lands.
        BlockHighlight.clearPreview()
        PaintedOverlay.invalidate()

        PaintArea.lastPaletteApplied = rerollable
        PaintArea.lastRules = LinkedHashMap(PaintArea.rules)
        screen.status(Component.translatable("austrianpainter.area.replaced_rules", painted, groups.size))
    }

    /** Shortcut: point the selection at the active palette, then apply everything. */
    fun replaceRandom() {
        assignPalette()
        replace()
    }

    /** Redraws the palette-drawn part of the previous apply. Same positions, a fresh roll. */
    fun reroll() {
        val batches = PaintArea.lastPaletteApplied
        if (batches.isEmpty()) return

        val random = RandomSource.create()
        val groups = batches.mapNotNull { (name, positions) ->
            val picker = paletteNamed(name).picker() ?: return@mapNotNull null
            PaintRules.Group(positions) { picker.next(random) }
        }

        val painted = PaintStorage.paintGroups(groups)
        screen.status(Component.translatable("austrianpainter.area.rerolled", painted))
    }

    /** Drops the previous apply's rules onto the box in hand and runs them. */
    fun reapplyLast() {
        if (PaintArea.lastRules.isEmpty()) return
        PaintArea.rules.clear()
        PaintArea.rules.putAll(PaintArea.lastRules)
        replace()
    }

    /** Same as [clearPainted], narrowed to whatever the highlighted rows match. */
    fun clearSelectedPaint() {
        val level = Minecraft.getInstance().level ?: return
        if (!PaintArea.complete || tooBig() || PaintArea.selected.isEmpty()) return

        val positions = AreaScan.positionsFor(level, PaintArea.selected).values.flatten()
        if (positions.isEmpty()) {
            screen.status(Component.translatable("austrianpainter.area.matched_nothing"))
            return
        }

        ConfirmAction.ask(
            screen,
            Component.translatable("austrianpainter.area.clear_selected.confirm.title"),
            Component.translatable("austrianpainter.area.clear_selected.confirm.message", positions.size),
        ) {
            val cleared = PaintStorage.unpaintPositions(positions)
            screen.status(Component.translatable("austrianpainter.area.cleared", cleared))
        }
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

    // ------------------------------------------------------------------ rulesets

    fun saveRuleset() {
        val preset = ruleset()
        preset.map.clear()
        preset.map.putAll(PaintArea.rules)
        PresetStores.rulesets.saveActive()
        screen.status(
            Component.translatable(
                "austrianpainter.area.ruleset_saved",
                PresetStores.rulesets.activeName,
                preset.size,
            ),
        )
    }

    fun loadRuleset() {
        val preset = ruleset()
        PaintArea.rules.clear()
        PaintArea.rules.putAll(preset.map)
        screen.status(
            Component.translatable(
                "austrianpainter.area.ruleset_loaded",
                PresetStores.rulesets.activeName,
                preset.size,
            ),
        )
    }

    /** A rule can name a palette the player is not holding, so fall back to reading it off disk. */
    private fun paletteNamed(name: String): PalettePreset = PresetStores.palettes.read(name)

    // ------------------------------------------------------------------ text

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

    /** The three things the buttons read from, on one line: rules, selection, and what feeds them. */
    fun mappingText(): Component = Component.translatable(
        "austrianpainter.area.mapping_line",
        PaintArea.rules.size,
        PaintArea.selected.size,
        PresetStores.rulesets.activeName,
        PresetStores.palettes.activeName,
    )

    fun selectorName(selector: AreaSelector): Component = selector.displayName()

    fun targetName(target: AreaTarget): Component = target.displayName()

    fun emptyText(): Component = when {
        !PaintArea.complete -> KeyHints.cornerHint()
        tooBig() -> Component.translatable("austrianpainter.area.too_big", PaintArea.MAX_VOLUME)
        else -> Component.translatable("austrianpainter.area.scan_empty")
    }
}
