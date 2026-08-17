package com.maxisch.paint.settings

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.maxisch.paint.settings.ApLog.LOGGER
import net.minecraft.core.registries.BuiltInRegistries
import java.nio.file.Path
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import com.maxisch.paint.rule.AreaTarget
import com.maxisch.paint.rule.BossZone
import com.maxisch.paint.rule.BossZones
import com.maxisch.paint.rule.DeviceArray
import com.maxisch.paint.rule.DeviceColumns
import com.maxisch.paint.rule.DeviceSource
import com.maxisch.paint.preset.PresetCodec
import com.maxisch.paint.preset.PresetKind
import com.maxisch.paint.preset.PresetStores
import com.maxisch.paint.rule.ZoneSourceRule

/** Which presets a world last used. Both kinds are bound independently. */
data class WorldBinding(var blocks: String, var types: String)

private val BOSS_KEY = Regex("^B\\d+$")

/** `config/ap/settings.json`. */
object ApSettings {

    const val DEFAULT_PRESET = "default"

    const val DEFAULT_AREA_OUTLINE = 0xFFFFAA00.toInt()
    const val DEFAULT_AREA_FILL = 0x30FFAA00

    /** Green for what is already painted, cyan for what an apply is about to paint. */
    const val DEFAULT_PAINTED_OUTLINE = 0xFF55FF55.toInt()
    const val DEFAULT_PREVIEW_OUTLINE = 0xFF55FFFF.toInt()

    const val MIN_OVERLAY_RADIUS = 4
    const val MAX_OVERLAY_RADIUS = 48

    /** Written for a device rule the player turned off, so "off" survives a round trip. */
    private const val DEVICE_RULE_OFF = "none"

    var defaultBlockPreset: String = DEFAULT_PRESET
    var defaultTypePreset: String = DEFAULT_PRESET

    /** Fallback preset used when the current room (if any) has no dungeon-room preset bound to it. */
    var defaultRoomPreset: String = DEFAULT_PRESET

    /** Fallback preset used when the current boss room (if any) has no boss preset bound to it. */
    var defaultBossPreset: String = DEFAULT_PRESET

    /** Fallback palette used when the current room (if any) has no palette bound to it. */
    var activePalette: String = DEFAULT_PRESET

    /** The area ruleset in hand. Global: a ruleset describes a look, not a place. */
    var activeRuleset: String = DEFAULT_PRESET

    /** How many donors the block picker offers back, newest first. One row of its grid. */
    const val MAX_RECENT_DONORS = 9

    /**
     * Block ids of the donors picked most recently, newest first. Kept as ids rather than blocks so
     * a resource pack or mod change cannot fail the settings load; the picker resolves them and
     * silently drops whatever no longer exists.
     */
    private val recentDonors = mutableListOf<String>()

    fun recentDonorIds(): List<String> = recentDonors

    /** Moves [id] to the front, deduped and trimmed. Saves: the list is only touched on a pick. */
    fun rememberDonor(id: String) {
        recentDonors.remove(id)
        recentDonors.add(0, id)
        while (recentDonors.size > MAX_RECENT_DONORS) recentDonors.removeLast()
        save()
    }

    var brushRadius: Int = 1
    var paintSound: Boolean = true
    var showHud: Boolean = true

    /** Adds the brush-resize gesture to the HUD; on until the player turns it off. */
    var showHints: Boolean = true

    /** Adds the real block under the crosshair to the HUD; independent of showHud. */
    var showLookingAt: Boolean = true

    /** Master switch for every PaintKeys action; the KeyMapping objects stay registered either way. */
    var keybindsEnabled: Boolean = true

    /** Whether orienting a dungeon room with no paint in the active room preset posts a chat line. */
    var notifyUnpaintedRooms: Boolean = true

    /** Whether the one-time "press X to open the paint menu" chat line has already been sent. */
    var seenIntro: Boolean = false

    /** Master switch for the Catacombs room scope; off means paint is always world-absolute. */
    var dungeonRoomScope: Boolean = true

    /** ARGB. The fill is deliberately faint so the box never hides what is inside it. */
    var areaOutlineColor: Int = DEFAULT_AREA_OUTLINE
    var areaFillColor: Int = DEFAULT_AREA_FILL

    /** Outlines painted blocks around the player, since paint is otherwise invisible as paint. */
    var showPaintedOverlay: Boolean = false
    var paintedOverlayRadius: Int = 16
    var paintedOverlayColor: Int = DEFAULT_PAINTED_OUTLINE

    /** Colour of the "this is what Replace would touch" outline in the area tab. */
    var areaPreviewColor: Int = DEFAULT_PREVIEW_OUTLINE

    // ---------------------------------------------------------------- device columns

    /** Repaints the F7 phase-2 device pillars; see [DeviceColumns]. */
    var deviceEnabled: Boolean = false

    /**
     * Which boss preset ("config") the P1/P2/P3 rules below currently read and write - the same
     * name [PresetStores.bosses] has loaded. Rules travel with the active boss preset: walking into
     * a boss floor bound to a different preset, or activating one by hand in the Presets tab, swaps
     * which slice of [deviceRulesByConfig]/[zoneRulesByConfig] every rule function below sees.
     */
    private val activeConfig: String get() = PresetStores.bosses.activeName

    /**
     * Outer key is the boss preset name (see [activeConfig]); inner keyed `"<array>.<source>"`. A
     * key present with a null value is a rule the player turned off, which is not the same as one
     * that was never written - an absent key falls back to the array's own colour so a settings
     * file from before the feature existed (or before a given config had any rules of its own)
     * picks it up.
     */
    private val deviceRulesByConfig = LinkedHashMap<String, LinkedHashMap<String, AreaTarget?>>()

    private fun deviceKey(array: DeviceArray, source: DeviceSource) = "${array.key}.${source.key}"

    fun deviceRule(array: DeviceArray, source: DeviceSource): AreaTarget? {
        val key = deviceKey(array, source)
        val bucket = deviceRulesByConfig[activeConfig]
        if (bucket?.containsKey(key) == true) return bucket[key]
        return AreaTarget.Donor(array.defaultDonor)
    }

    fun setDeviceRule(array: DeviceArray, source: DeviceSource, target: AreaTarget?) {
        deviceRulesByConfig.getOrPut(activeConfig) { LinkedHashMap() }[deviceKey(array, source)] = target
        save()
    }

    /** Back to each array's own glass, by forgetting every rule of the active config rather than by
     *  writing defaults. Other configs' rules are untouched. */
    fun resetDeviceRules() {
        deviceRulesByConfig[activeConfig]?.clear()
        save()
    }

    // ---------------------------------------------------------------- boss zones (P1 conveyer, P3 devices)

    /** P3 - every zone with [BossZone.p1] unset. P1 has its own [conveyorEnabled]: they are two
     *  independent boss phases, not one feature with a shared switch. */
    var zonesEnabled: Boolean = false

    /** P1 - every zone with [BossZone.p1] set. */
    var conveyorEnabled: Boolean = false

    /** Keyed "<zone>.<blockId>[.lit|.unlit]", same present-with-null-means-off contract and same
     *  per-[activeConfig] outer key as [deviceRulesByConfig]. Shared by P1 and P3 - the two are the
     *  same underlying rule map, only their enabled flag and their reset scope (see below) are
     *  independent. */
    private val zoneRulesByConfig = LinkedHashMap<String, LinkedHashMap<String, AreaTarget?>>()

    private fun zoneRuleKey(rule: ZoneSourceRule): String {
        val blockId = BuiltInRegistries.BLOCK.getKey(rule.block)
        val suffix = when (rule.lit) {
            true -> ".lit"
            false -> ".unlit"
            null -> ""
        }
        return "${rule.zone.key}.$blockId$suffix"
    }

    fun zoneRule(rule: ZoneSourceRule): AreaTarget? {
        val key = zoneRuleKey(rule)
        val bucket = zoneRulesByConfig[activeConfig]
        if (bucket?.containsKey(key) == true) return bucket[key]
        return rule.default
    }

    fun setZoneRule(rule: ZoneSourceRule, target: AreaTarget?) {
        zoneRulesByConfig.getOrPut(activeConfig) { LinkedHashMap() }[zoneRuleKey(rule)] = target
        save()
    }

    /** P1 can be more than one zone (see [BossZone.p1]), so scoping a reset to "P1" means "starts
     *  with any P1 zone's key", not one fixed prefix. */
    private val p1ZonePrefixes get() = BossZone.entries.filter { it.p1 }.map { "${it.key}." }

    /** P1 only, active config only. */
    fun resetConveyorRules() {
        zoneRulesByConfig[activeConfig]?.keys?.removeAll { key -> p1ZonePrefixes.any { key.startsWith(it) } }
        save()
    }

    /** P3 only, active config only - every zone rule whose zone is not a P1 zone. */
    fun resetZoneRules() {
        zoneRulesByConfig[activeConfig]?.keys?.removeAll { key -> p1ZonePrefixes.none { key.startsWith(it) } }
        save()
    }

    // ---------------------------------------------------------------- replace fluid

    /** Client-wide default; overridden per boss preset by [fluidOverridesByConfig] while
     *  standing in a boss fight whose active preset has one set - see the `effective*` getters. */
    var waterAsLava: Boolean = false
    var lavaAsWater: Boolean = false
    var waterTintEnabled: Boolean = false
    var waterTintColor: Int = -1
    var waterTintFlat: Boolean = false
    var lavaTintEnabled: Boolean = false
    var lavaTintColor: Int = -1
    var lavaTintFlat: Boolean = false

    /** One field left null means "fall back to the matching global value above" - same
     *  present-with-null-means-off shape [deviceRulesByConfig] uses, just per field instead of
     *  per rule since there are only eight of these rather than dozens. */
    data class FluidOverride(
        val waterAsLava: Boolean? = null,
        val lavaAsWater: Boolean? = null,
        val waterTintEnabled: Boolean? = null,
        val waterTintColor: Int? = null,
        val waterTintFlat: Boolean? = null,
        val lavaTintEnabled: Boolean? = null,
        val lavaTintColor: Int? = null,
        val lavaTintFlat: Boolean? = null,
    )

    private val fluidOverridesByConfig = LinkedHashMap<String, FluidOverride>()

    /** Bound to whichever boss preset is currently active, not to physically standing in the arena -
     *  fluid replacement is a pure rendering rule, not tied to a fixed in-arena position the way
     *  device/zone rules are, so it applies wherever [activeConfig] does. Null when that config has
     *  no override stored. */
    private fun activeFluidOverride(): FluidOverride? = fluidOverridesByConfig[activeConfig]

    val effectiveWaterAsLava: Boolean get() = activeFluidOverride()?.waterAsLava ?: waterAsLava
    val effectiveLavaAsWater: Boolean get() = activeFluidOverride()?.lavaAsWater ?: lavaAsWater
    val effectiveWaterTintEnabled: Boolean get() = activeFluidOverride()?.waterTintEnabled ?: waterTintEnabled
    val effectiveWaterTintColor: Int get() = activeFluidOverride()?.waterTintColor ?: waterTintColor
    val effectiveWaterTintFlat: Boolean get() = activeFluidOverride()?.waterTintFlat ?: waterTintFlat
    val effectiveLavaTintEnabled: Boolean get() = activeFluidOverride()?.lavaTintEnabled ?: lavaTintEnabled
    val effectiveLavaTintColor: Int get() = activeFluidOverride()?.lavaTintColor ?: lavaTintColor
    val effectiveLavaTintFlat: Boolean get() = activeFluidOverride()?.lavaTintFlat ?: lavaTintFlat

    /** Whether [config] currently has an override bucket at all, for the DungeonTab toggle. */
    fun hasFluidOverride(config: String): Boolean = fluidOverridesByConfig.containsKey(config)

    /** [update] returns the new override; an all-null result removes the entry entirely so an
     *  unused boss preset never carries a stale, all-fallback-through bucket. */
    fun setFluidOverride(config: String, update: (FluidOverride) -> FluidOverride) {
        val next = update(fluidOverridesByConfig[config] ?: FluidOverride())
        if (next == FluidOverride()) fluidOverridesByConfig.remove(config) else fluidOverridesByConfig[config] = next
        save()
    }

    private val worldPresets = LinkedHashMap<String, WorldBinding>()

    /** Room scope key to block-type preset, swapped in while that room is the active scope. */
    private val roomTypePresets = LinkedHashMap<String, String>()

    /** Dungeon-room name to positional room preset, swapped in while that room is the active scope. */
    private val roomPresets = LinkedHashMap<String, String>()

    /** Boss floor key (`B<floor>`) to positional boss preset, swapped in while it is the active scope. */
    private val bossPresets = LinkedHashMap<String, String>()

    /** Room scope key to palette preset, swapped in while that room is the active scope. */
    private val roomPalettePresets = LinkedHashMap<String, String>()

    fun roomTypePresetFor(scopeKey: String): String? = roomTypePresets[scopeKey]

    fun bindRoomTypes(scopeKey: String, preset: String?) {
        if (preset == null) roomTypePresets.remove(scopeKey) else roomTypePresets[scopeKey] = preset
        save()
    }

    fun roomPresetFor(key: String): String = roomPresets[key] ?: defaultRoomPreset

    fun bindRoomPreset(key: String, preset: String?) {
        if (preset == null) roomPresets.remove(key) else roomPresets[key] = preset
        save()
    }

    fun bossPresetFor(key: String): String = bossPresets[key] ?: defaultBossPreset

    fun bindBossPreset(key: String, preset: String?) {
        if (preset == null) bossPresets.remove(key) else bossPresets[key] = preset
        save()
    }

    fun roomPalettePresetFor(scopeKey: String): String? = roomPalettePresets[scopeKey]

    fun bindRoomPalettes(scopeKey: String, preset: String?) {
        if (preset == null) roomPalettePresets.remove(scopeKey) else roomPalettePresets[scopeKey] = preset
        save()
    }

    fun blockPresetFor(worldKey: String): String =
        worldPresets[worldKey]?.blocks ?: defaultBlockPreset

    fun typePresetFor(worldKey: String): String =
        worldPresets[worldKey]?.types ?: defaultTypePreset

    fun bindBlocks(worldKey: String, preset: String) {
        binding(worldKey).blocks = preset
        save()
    }

    fun bindTypes(worldKey: String, preset: String) {
        binding(worldKey).types = preset
        save()
    }

    private fun binding(worldKey: String): WorldBinding =
        worldPresets.getOrPut(worldKey) { WorldBinding(defaultBlockPreset, defaultTypePreset) }

    /** Called when a preset is renamed, so bindings keep pointing at it. */
    fun renamePreset(kind: PresetKind, from: String, to: String) {
        when (kind) {
            PresetKind.BLOCKS -> {
                for (bound in worldPresets.values) if (bound.blocks == from) bound.blocks = to
                if (defaultBlockPreset == from) defaultBlockPreset = to
            }

            PresetKind.ROOMS -> {
                for (room in roomPresets.keys.toList()) {
                    if (roomPresets[room] == from) roomPresets[room] = to
                }
                if (defaultRoomPreset == from) defaultRoomPreset = to
            }

            PresetKind.BOSSES -> {
                for (room in bossPresets.keys.toList()) {
                    if (bossPresets[room] == from) bossPresets[room] = to
                }
                if (defaultBossPreset == from) defaultBossPreset = to

                // The P1/P2/P3 rules bound to this boss preset's name move with it, same as the
                // preset's own file does (PresetStore.rename renames the file, not what is in it).
                deviceRulesByConfig.remove(from)?.let { deviceRulesByConfig[to] = it }
                zoneRulesByConfig.remove(from)?.let { zoneRulesByConfig[to] = it }
                fluidOverridesByConfig.remove(from)?.let { fluidOverridesByConfig[to] = it }
                DeviceColumns.invalidate()
                BossZones.invalidate()
            }

            PresetKind.TYPES -> {
                for (bound in worldPresets.values) if (bound.types == from) bound.types = to
                for (room in roomTypePresets.keys.toList()) {
                    if (roomTypePresets[room] == from) roomTypePresets[room] = to
                }
                if (defaultTypePreset == from) defaultTypePreset = to
            }

            PresetKind.PALETTES -> {
                for (room in roomPalettePresets.keys.toList()) {
                    if (roomPalettePresets[room] == from) roomPalettePresets[room] = to
                }
                if (activePalette == from) activePalette = to
                // A device rule can name a palette too, and a rule left pointing at the old name
                // fails silently - it just falls back to a colour. (Ruleset preset files have the
                // same problem and are not rewritten here; they live on disk, not in settings.)
                var renamed = false
                for (bucket in deviceRulesByConfig.values) {
                    for (key in bucket.keys.toList()) {
                        val target = bucket[key]
                        if (target is AreaTarget.Palette && target.name == from) {
                            bucket[key] = AreaTarget.Palette(to)
                            renamed = true
                        }
                    }
                }
                if (renamed) DeviceColumns.invalidate()

                var zoneRenamed = false
                for (bucket in zoneRulesByConfig.values) {
                    for (key in bucket.keys.toList()) {
                        val target = bucket[key]
                        if (target is AreaTarget.Palette && target.name == from) {
                            bucket[key] = AreaTarget.Palette(to)
                            zoneRenamed = true
                        }
                    }
                }
                if (zoneRenamed) BossZones.invalidate()
            }

            PresetKind.RULESETS -> {
                if (activeRuleset == from) activeRuleset = to
            }
        }
        save()
    }

    // ---------------------------------------------------------------- io

    /** One config's slice of a device/zone rule map, read from its "<key>": "<raw>" JSON object. */
    private fun parseRuleBucket(json: JsonObject, settingsPath: Path): LinkedHashMap<String, AreaTarget?> {
        val bucket = LinkedHashMap<String, AreaTarget?>()
        json.entrySet().forEach { (key, value) ->
            val raw = value.asString
            bucket[key] =
                if (raw == DEVICE_RULE_OFF) null
                else PresetCodec.target(raw, settingsPath) ?: return@forEach
        }
        return bucket
    }

    fun load() {
        ApPaths.ensureDirectories()

        val path = ApPaths.settingsFile
        if (path.notExists()) {
            LegacyMigration.run()
            save()
            return
        }

        runCatching {
            val root = JsonParser.parseString(path.readText()).asJsonObject
            defaultBlockPreset = root.get("defaultBlockPreset")?.asString ?: DEFAULT_PRESET
            defaultTypePreset = root.get("defaultTypePreset")?.asString ?: DEFAULT_PRESET
            defaultRoomPreset = root.get("defaultRoomPreset")?.asString ?: DEFAULT_PRESET
            defaultBossPreset = root.get("defaultBossPreset")?.asString ?: DEFAULT_PRESET
            activePalette = root.get("activePalette")?.asString ?: DEFAULT_PRESET
            activeRuleset = root.get("activeRuleset")?.asString ?: DEFAULT_PRESET
            brushRadius = root.get("brushRadius")?.asInt ?: 1
            paintSound = root.get("paintSound")?.asBoolean ?: true
            showHud = root.get("showHud")?.asBoolean ?: true
            showHints = root.get("showHints")?.asBoolean ?: true
            showLookingAt = root.get("showLookingAt")?.asBoolean ?: true
            keybindsEnabled = root.get("keybindsEnabled")?.asBoolean ?: true
            notifyUnpaintedRooms = root.get("notifyUnpaintedRooms")?.asBoolean ?: true
            // Defaults false so configs written before the hint existed still get it once.
            seenIntro = root.get("seenIntro")?.asBoolean ?: false
            dungeonRoomScope = root.get("dungeonRoomScope")?.asBoolean ?: true
            areaOutlineColor = root.get("areaOutlineColor")?.asInt ?: DEFAULT_AREA_OUTLINE
            areaFillColor = root.get("areaFillColor")?.asInt ?: DEFAULT_AREA_FILL
            showPaintedOverlay = root.get("showPaintedOverlay")?.asBoolean ?: false
            paintedOverlayRadius = (root.get("paintedOverlayRadius")?.asInt ?: 16)
                .coerceIn(MIN_OVERLAY_RADIUS, MAX_OVERLAY_RADIUS)
            paintedOverlayColor = root.get("paintedOverlayColor")?.asInt ?: DEFAULT_PAINTED_OUTLINE
            areaPreviewColor = root.get("areaPreviewColor")?.asInt ?: DEFAULT_PREVIEW_OUTLINE
            waterAsLava = root.get("waterAsLava")?.asBoolean ?: false
            lavaAsWater = root.get("lavaAsWater")?.asBoolean ?: false
            waterTintEnabled = root.get("waterTintEnabled")?.asBoolean ?: false
            waterTintColor = root.get("waterTintColor")?.asInt ?: -1
            waterTintFlat = root.get("waterTintFlat")?.asBoolean ?: false
            lavaTintEnabled = root.get("lavaTintEnabled")?.asBoolean ?: false
            lavaTintColor = root.get("lavaTintColor")?.asInt ?: -1
            lavaTintFlat = root.get("lavaTintFlat")?.asBoolean ?: false

            roomTypePresets.clear()
            root.getAsJsonObject("roomTypePresets")?.entrySet()?.forEach { (room, preset) ->
                roomTypePresets[room] = preset.asString
            }

            roomPresets.clear()
            bossPresets.clear()
            root.getAsJsonObject("roomPresets")?.entrySet()?.forEach { (room, preset) ->
                roomPresets[room] = preset.asString
            }
            root.getAsJsonObject("bossPresets")?.entrySet()?.forEach { (room, preset) ->
                bossPresets[room] = preset.asString
            }
            // One-time migration: before rooms and bosses became independent preset kinds, both
            // were bound through one combined map. Split it by key shape so old bindings survive.
            root.getAsJsonObject("roomBlockPresets")?.entrySet()?.forEach { (room, preset) ->
                if (BOSS_KEY.matches(room)) bossPresets.putIfAbsent(room, preset.asString)
                else roomPresets.putIfAbsent(room, preset.asString)
            }

            roomPalettePresets.clear()
            root.getAsJsonObject("roomPalettePresets")?.entrySet()?.forEach { (room, preset) ->
                roomPalettePresets[room] = preset.asString
            }

            deviceRulesByConfig.clear()
            root.getAsJsonObject("deviceColumns")?.let { device ->
                deviceEnabled = device.get("enabled")?.asBoolean ?: false
                val byConfig = device.getAsJsonObject("rulesByConfig")
                if (byConfig != null) {
                    byConfig.entrySet().forEach { (config, rules) ->
                        deviceRulesByConfig[config] = parseRuleBucket(rules.asJsonObject, path)
                    }
                } else {
                    // Migration: a settings file from before rules were split per boss preset had
                    // one flat "rules" object - treat it as the "default" preset's rules rather
                    // than losing it.
                    device.getAsJsonObject("rules")?.let {
                        deviceRulesByConfig[DEFAULT_PRESET] = parseRuleBucket(it, path)
                    }
                }
            }

            zoneRulesByConfig.clear()
            root.getAsJsonObject("bossZones")?.let { zones ->
                zonesEnabled = zones.get("enabled")?.asBoolean ?: false
                // conveyorEnabled is new; a settings file from before P1 and P3 split falls back to
                // whatever the old shared "enabled" was, so an existing save does not silently lose
                // its conveyer rules on update.
                conveyorEnabled = zones.get("conveyorEnabled")?.asBoolean ?: zonesEnabled
                val byConfig = zones.getAsJsonObject("rulesByConfig")
                if (byConfig != null) {
                    byConfig.entrySet().forEach { (config, rules) ->
                        zoneRulesByConfig[config] = parseRuleBucket(rules.asJsonObject, path)
                    }
                } else {
                    // Same migration as deviceColumns above.
                    zones.getAsJsonObject("rules")?.let {
                        zoneRulesByConfig[DEFAULT_PRESET] = parseRuleBucket(it, path)
                    }
                }
            }

            fluidOverridesByConfig.clear()
            root.getAsJsonObject("fluidOverridesByConfig")?.entrySet()?.forEach { (config, value) ->
                val obj = value.asJsonObject
                fluidOverridesByConfig[config] = FluidOverride(
                    waterAsLava = obj.get("waterAsLava")?.asBoolean,
                    lavaAsWater = obj.get("lavaAsWater")?.asBoolean,
                    waterTintEnabled = obj.get("waterTintEnabled")?.asBoolean,
                    waterTintColor = obj.get("waterTintColor")?.asInt,
                    waterTintFlat = obj.get("waterTintFlat")?.asBoolean,
                    lavaTintEnabled = obj.get("lavaTintEnabled")?.asBoolean,
                    lavaTintColor = obj.get("lavaTintColor")?.asInt,
                    lavaTintFlat = obj.get("lavaTintFlat")?.asBoolean,
                )
            }

            recentDonors.clear()
            root.getAsJsonArray("recentDonors")?.forEach { id ->
                if (recentDonors.size < MAX_RECENT_DONORS) recentDonors.add(id.asString)
            }

            worldPresets.clear()
            root.getAsJsonObject("worldPresets")?.entrySet()?.forEach { (world, value) ->
                val bound = value.asJsonObject
                worldPresets[world] = WorldBinding(
                    bound.get("blocks")?.asString ?: defaultBlockPreset,
                    bound.get("types")?.asString ?: defaultTypePreset,
                )
            }
        }.onFailure { LOGGER.error("Could not read {}", path, it) }
    }

    fun save() {
        val root = JsonObject().apply {
            addProperty("defaultBlockPreset", defaultBlockPreset)
            addProperty("defaultTypePreset", defaultTypePreset)
            addProperty("defaultRoomPreset", defaultRoomPreset)
            addProperty("defaultBossPreset", defaultBossPreset)
            addProperty("activePalette", activePalette)
            addProperty("activeRuleset", activeRuleset)
            addProperty("brushRadius", brushRadius)
            addProperty("paintSound", paintSound)
            addProperty("showHud", showHud)
            addProperty("showHints", showHints)
            addProperty("showLookingAt", showLookingAt)
            addProperty("keybindsEnabled", keybindsEnabled)
            addProperty("notifyUnpaintedRooms", notifyUnpaintedRooms)
            addProperty("seenIntro", seenIntro)
            addProperty("dungeonRoomScope", dungeonRoomScope)
            addProperty("areaOutlineColor", areaOutlineColor)
            addProperty("areaFillColor", areaFillColor)
            addProperty("showPaintedOverlay", showPaintedOverlay)
            addProperty("paintedOverlayRadius", paintedOverlayRadius)
            addProperty("paintedOverlayColor", paintedOverlayColor)
            addProperty("areaPreviewColor", areaPreviewColor)
            addProperty("waterAsLava", waterAsLava)
            addProperty("lavaAsWater", lavaAsWater)
            addProperty("waterTintEnabled", waterTintEnabled)
            addProperty("waterTintColor", waterTintColor)
            addProperty("waterTintFlat", waterTintFlat)
            addProperty("lavaTintEnabled", lavaTintEnabled)
            addProperty("lavaTintColor", lavaTintColor)
            addProperty("lavaTintFlat", lavaTintFlat)

            val roomBindings = JsonObject()
            for ((room, preset) in roomTypePresets) roomBindings.addProperty(room, preset)
            add("roomTypePresets", roomBindings)

            val roomPresetBindings = JsonObject()
            for ((room, preset) in roomPresets) roomPresetBindings.addProperty(room, preset)
            add("roomPresets", roomPresetBindings)

            val bossPresetBindings = JsonObject()
            for ((room, preset) in bossPresets) bossPresetBindings.addProperty(room, preset)
            add("bossPresets", bossPresetBindings)

            val roomPaletteBindings = JsonObject()
            for ((room, preset) in roomPalettePresets) roomPaletteBindings.addProperty(room, preset)
            add("roomPalettePresets", roomPaletteBindings)

            add(
                "deviceColumns",
                JsonObject().apply {
                    addProperty("enabled", deviceEnabled)
                    val byConfig = JsonObject()
                    // Every config that has ever been visited gets its full rule set written,
                    // including the entries still on their default, so the file shows what each one
                    // will actually do rather than only what was changed - same reasoning as before
                    // these were split per config, just once per config now instead of once overall.
                    for (config in deviceRulesByConfig.keys.ifEmpty { setOf(activeConfig) }) {
                        val bucket = deviceRulesByConfig[config]
                        val rules = JsonObject()
                        for (array in DeviceArray.entries) {
                            for (source in DeviceSource.entries) {
                                val key = deviceKey(array, source)
                                val target = if (bucket?.containsKey(key) == true) bucket[key] else AreaTarget.Donor(array.defaultDonor)
                                rules.addProperty(
                                    key,
                                    if (target == null) DEVICE_RULE_OFF else PresetCodec.targetValue(target),
                                )
                            }
                        }
                        byConfig.add(config, rules)
                    }
                    add("rulesByConfig", byConfig)
                },
            )

            add(
                "bossZones",
                JsonObject().apply {
                    addProperty("enabled", zonesEnabled)
                    addProperty("conveyorEnabled", conveyorEnabled)
                    val byConfig = JsonObject()
                    for (config in zoneRulesByConfig.keys.ifEmpty { setOf(activeConfig) }) {
                        val bucket = zoneRulesByConfig[config]
                        val rules = JsonObject()
                        for (rule in BossZones.RULES) {
                            val key = zoneRuleKey(rule)
                            val target = if (bucket?.containsKey(key) == true) bucket[key] else rule.default
                            rules.addProperty(
                                key,
                                if (target == null) DEVICE_RULE_OFF else PresetCodec.targetValue(target),
                            )
                        }
                        byConfig.add(config, rules)
                    }
                    add("rulesByConfig", byConfig)
                },
            )

            val fluidOverrides = JsonObject()
            for ((config, override) in fluidOverridesByConfig) {
                fluidOverrides.add(
                    config,
                    JsonObject().apply {
                        override.waterAsLava?.let { addProperty("waterAsLava", it) }
                        override.lavaAsWater?.let { addProperty("lavaAsWater", it) }
                        override.waterTintEnabled?.let { addProperty("waterTintEnabled", it) }
                        override.waterTintColor?.let { addProperty("waterTintColor", it) }
                        override.waterTintFlat?.let { addProperty("waterTintFlat", it) }
                        override.lavaTintEnabled?.let { addProperty("lavaTintEnabled", it) }
                        override.lavaTintColor?.let { addProperty("lavaTintColor", it) }
                        override.lavaTintFlat?.let { addProperty("lavaTintFlat", it) }
                    },
                )
            }
            add("fluidOverridesByConfig", fluidOverrides)

            val recent = JsonArray()
            for (id in recentDonors) recent.add(id)
            add("recentDonors", recent)

            val bindings = JsonObject()
            for ((world, bound) in worldPresets) {
                bindings.add(
                    world,
                    JsonObject().apply {
                        addProperty("blocks", bound.blocks)
                        addProperty("types", bound.types)
                    },
                )
            }
            add("worldPresets", bindings)
        }

        runCatching {
            ApPaths.ensureDirectories()
            ApPaths.settingsFile.writeText(ApJson.PRETTY.toJson(root) + "\n")
        }.onFailure { LOGGER.error("Could not write {}", ApPaths.settingsFile, it) }
    }

    internal fun bindDuringMigration(worldKey: String, blocks: String?, types: String?) {
        val bound = binding(worldKey)
        blocks?.let { bound.blocks = it }
        types?.let { bound.types = it }
    }
}
