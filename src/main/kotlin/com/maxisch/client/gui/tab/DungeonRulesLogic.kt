package com.maxisch.client.gui.tab

import com.maxisch.paint.rule.AreaTarget
import com.maxisch.paint.rule.DeviceArray
import com.maxisch.paint.rule.DeviceSource
import com.maxisch.paint.preset.PresetStores

/** One device-column rule key: which array, which of its two source blocks. */
data class DeviceRule(val array: DeviceArray, val source: DeviceSource)

/**
 * The non-UI half of [DungeonTab]: the fixed rule list and the palette-cycling step shared by both
 * the device-column and boss-zone editors. Named for the tab rather than for either rule set,
 * since both editors in the tab use it identically - mirrors how [AreaScanLogic] holds AreaTab's
 * scan/apply logic while the tab itself keeps the widgets and labels.
 */
object DungeonRulesLogic {

    val deviceRules: List<DeviceRule> =
        DeviceArray.entries.flatMap { array -> DeviceSource.entries.map { DeviceRule(array, it) } }

    /**
     * Next palette name after [current], or the first palette if [current] isn't a palette rule.
     * Cycles rather than opening a picker: there are rarely more than a handful of palettes, and a
     * second modal for a list that short would cost more clicks than it saves.
     */
    fun nextPalette(current: AreaTarget?): String? {
        val names = PresetStores.palettes.listWithActive()
        if (names.isEmpty()) return null
        return if (current is AreaTarget.Palette) {
            names[(names.indexOf(current.name) + 1).mod(names.size)]
        } else {
            names.first()
        }
    }
}
