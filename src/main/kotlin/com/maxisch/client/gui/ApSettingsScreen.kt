package com.maxisch.client.gui

import com.maxisch.client.KeyHints
import com.maxisch.client.render.BlockHighlight
import com.maxisch.client.render.PaintedOverlay
import com.maxisch.dungeon.RoomTracker
import com.maxisch.paint.ApSettings
import com.maxisch.paint.PaintStorage
import com.maxisch.paint.PresetStores
import com.maxisch.paint.session.PaintBrush
import dev.isxander.yacl3.api.ButtonOption
import dev.isxander.yacl3.api.ConfigCategory
import dev.isxander.yacl3.api.Option
import dev.isxander.yacl3.api.OptionDescription
import dev.isxander.yacl3.api.OptionGroup
import dev.isxander.yacl3.api.YetAnotherConfigLib
import dev.isxander.yacl3.api.controller.ColorControllerBuilder
import dev.isxander.yacl3.api.controller.CyclingListControllerBuilder
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.awt.Color

/** The YACL settings screen. Preset creation and deletion live in the painter's Presets tab. */
object ApSettingsScreen {

    fun create(parent: Screen?): Screen {
        // Snapshot the names now so the cyclers have a stable value set for this screen's lifetime.
        val blockPresets = PresetStores.blocks.listWithActive()
        val typePresets = PresetStores.types.listWithActive()
        val palettes = PresetStores.palettes.listWithActive()

        var pendingBlockPreset = PresetStores.blocks.activeName
        var pendingTypePreset = PresetStores.types.activeName
        var pendingPalette = PresetStores.palettes.activeName

        return YetAnotherConfigLib.createBuilder()
            .title(Component.translatable("austrianpainter.settings.title"))
            .category(
                ConfigCategory.createBuilder()
                    .name(Component.translatable("austrianpainter.settings.general"))
                    .group(
                        OptionGroup.createBuilder()
                            .name(Component.translatable("austrianpainter.settings.general"))
                            .option(
                                Option.createBuilder<Boolean>()
                                    .name(Component.translatable("austrianpainter.settings.show_hud"))
                                    .description(
                                        OptionDescription.of(
                                            Component.translatable("austrianpainter.settings.show_hud.desc"),
                                        ),
                                    )
                                    .binding(true, { ApSettings.showHud }, { ApSettings.showHud = it })
                                    .controller { TickBoxControllerBuilder.create(it) }
                                    .build(),
                            )
                            .option(
                                Option.createBuilder<Boolean>()
                                    .name(Component.translatable("austrianpainter.settings.show_hints"))
                                    .description(
                                        OptionDescription.of(
                                            Component.translatable("austrianpainter.settings.show_hints.desc"),
                                        ),
                                    )
                                    .binding(true, { ApSettings.showHints }, { ApSettings.showHints = it })
                                    .controller { TickBoxControllerBuilder.create(it) }
                                    .build(),
                            )
                            .option(
                                Option.createBuilder<Boolean>()
                                    .name(Component.translatable("austrianpainter.settings.show_looking_at"))
                                    .description(
                                        OptionDescription.of(
                                            Component.translatable(
                                                "austrianpainter.settings.show_looking_at.desc",
                                            ),
                                        ),
                                    )
                                    .binding(
                                        true,
                                        { ApSettings.showLookingAt },
                                        { ApSettings.showLookingAt = it },
                                    )
                                    .controller { TickBoxControllerBuilder.create(it) }
                                    .build(),
                            )
                            .option(
                                Option.createBuilder<Boolean>()
                                    .name(Component.translatable("austrianpainter.settings.paint_sound"))
                                    .description(
                                        OptionDescription.of(
                                            Component.translatable("austrianpainter.settings.paint_sound.desc"),
                                        ),
                                    )
                                    .binding(true, { ApSettings.paintSound }, { ApSettings.paintSound = it })
                                    .controller { TickBoxControllerBuilder.create(it) }
                                    .build(),
                            )
                            .option(
                                Option.createBuilder<Int>()
                                    .name(Component.translatable("austrianpainter.settings.brush_radius"))
                                    .description(
                                        OptionDescription.of(
                                            // The cube side is derived, not written into the string:
                                            // changing MAX_RADIUS used to leave this quietly lying.
                                            Component.translatable(
                                                "austrianpainter.settings.brush_radius.desc",
                                                2 * PaintBrush.MAX_RADIUS - 1,
                                            ),
                                            KeyHints.resizeHint(),
                                        ),
                                    )
                                    .binding(
                                        PaintBrush.MIN_RADIUS,
                                        { ApSettings.brushRadius },
                                        { ApSettings.brushRadius = it },
                                    )
                                    .controller {
                                        IntegerSliderControllerBuilder.create(it)
                                            .range(PaintBrush.MIN_RADIUS, PaintBrush.MAX_RADIUS)
                                            .step(1)
                                    }
                                    .build(),
                            )
                            .option(
                                Option.createBuilder<Boolean>()
                                    .name(Component.translatable("austrianpainter.settings.keybinds_enabled"))
                                    .description(
                                        OptionDescription.of(
                                            Component.translatable(
                                                "austrianpainter.settings.keybinds_enabled.desc",
                                            ),
                                        ),
                                    )
                                    .binding(
                                        true,
                                        { ApSettings.keybindsEnabled },
                                        { ApSettings.keybindsEnabled = it },
                                    )
                                    .controller { TickBoxControllerBuilder.create(it) }
                                    .build(),
                            )
                            .build(),
                    )
                    .group(
                        OptionGroup.createBuilder()
                            .name(Component.translatable("austrianpainter.settings.area"))
                            .option(
                                Option.createBuilder<Color>()
                                    .name(Component.translatable("austrianpainter.settings.area_outline"))
                                    .description(
                                        OptionDescription.of(
                                            Component.translatable("austrianpainter.settings.area_outline.desc"),
                                        ),
                                    )
                                    .binding(
                                        Color(ApSettings.DEFAULT_AREA_OUTLINE, true),
                                        { Color(ApSettings.areaOutlineColor, true) },
                                        { ApSettings.areaOutlineColor = it.rgb },
                                    )
                                    .controller { ColorControllerBuilder.create(it).allowAlpha(true) }
                                    .build(),
                            )
                            .option(
                                Option.createBuilder<Color>()
                                    .name(Component.translatable("austrianpainter.settings.area_fill"))
                                    .description(
                                        OptionDescription.of(
                                            Component.translatable("austrianpainter.settings.area_fill.desc"),
                                        ),
                                    )
                                    .binding(
                                        Color(ApSettings.DEFAULT_AREA_FILL, true),
                                        { Color(ApSettings.areaFillColor, true) },
                                        { ApSettings.areaFillColor = it.rgb },
                                    )
                                    .controller { ColorControllerBuilder.create(it).allowAlpha(true) }
                                    .build(),
                            )
                            .option(
                                Option.createBuilder<Color>()
                                    .name(Component.translatable("austrianpainter.settings.preview_outline"))
                                    .description(
                                        OptionDescription.of(
                                            Component.translatable("austrianpainter.settings.preview_outline.desc"),
                                        ),
                                    )
                                    .binding(
                                        Color(ApSettings.DEFAULT_PREVIEW_OUTLINE, true),
                                        { Color(ApSettings.areaPreviewColor, true) },
                                        { ApSettings.areaPreviewColor = it.rgb },
                                    )
                                    .controller { ColorControllerBuilder.create(it).allowAlpha(true) }
                                    .build(),
                            )
                            .build(),
                    )
                    .group(
                        OptionGroup.createBuilder()
                            .name(Component.translatable("austrianpainter.settings.overlay"))
                            .description(
                                OptionDescription.of(
                                    Component.translatable("austrianpainter.settings.overlay.desc"),
                                ),
                            )
                            .option(
                                Option.createBuilder<Boolean>()
                                    .name(Component.translatable("austrianpainter.settings.overlay_show"))
                                    .binding(
                                        false,
                                        { ApSettings.showPaintedOverlay },
                                        {
                                            ApSettings.showPaintedOverlay = it
                                            PaintedOverlay.invalidate()
                                        },
                                    )
                                    .controller { TickBoxControllerBuilder.create(it) }
                                    .build(),
                            )
                            .option(
                                Option.createBuilder<Int>()
                                    .name(Component.translatable("austrianpainter.settings.overlay_radius"))
                                    .description(
                                        OptionDescription.of(
                                            Component.translatable(
                                                "austrianpainter.settings.overlay_radius.desc",
                                                BlockHighlight.MAX_BOXES,
                                            ),
                                        ),
                                    )
                                    .binding(
                                        16,
                                        { ApSettings.paintedOverlayRadius },
                                        {
                                            ApSettings.paintedOverlayRadius = it
                                            PaintedOverlay.invalidate()
                                        },
                                    )
                                    .controller {
                                        IntegerSliderControllerBuilder.create(it)
                                            .range(ApSettings.MIN_OVERLAY_RADIUS, ApSettings.MAX_OVERLAY_RADIUS)
                                            .step(2)
                                    }
                                    .build(),
                            )
                            .option(
                                Option.createBuilder<Color>()
                                    .name(Component.translatable("austrianpainter.settings.overlay_color"))
                                    .binding(
                                        Color(ApSettings.DEFAULT_PAINTED_OUTLINE, true),
                                        { Color(ApSettings.paintedOverlayColor, true) },
                                        {
                                            ApSettings.paintedOverlayColor = it.rgb
                                            PaintedOverlay.invalidate()
                                        },
                                    )
                                    .controller { ColorControllerBuilder.create(it).allowAlpha(true) }
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .category(
                ConfigCategory.createBuilder()
                    .name(Component.translatable("austrianpainter.settings.dungeon"))
                    .group(
                        OptionGroup.createBuilder()
                            .name(Component.translatable("austrianpainter.settings.dungeon"))
                            .description(
                                OptionDescription.of(
                                    Component.translatable("austrianpainter.settings.dungeon.desc"),
                                ),
                            )
                            .option(
                                Option.createBuilder<Boolean>()
                                    .name(Component.translatable("austrianpainter.settings.room_scope"))
                                    .description(
                                        OptionDescription.of(
                                            Component.translatable("austrianpainter.settings.room_scope.desc"),
                                        ),
                                    )
                                    .binding(true, { ApSettings.dungeonRoomScope }, { ApSettings.dungeonRoomScope = it })
                                    .controller { TickBoxControllerBuilder.create(it) }
                                    .build(),
                            )
                            .option(
                                ButtonOption.createBuilder()
                                    .name(Component.translatable("austrianpainter.settings.rescan"))
                                    .description(
                                        OptionDescription.of(
                                            Component.translatable("austrianpainter.settings.rescan.desc"),
                                        ),
                                    )
                                    // Re-arms the floor detection as well as the layout: a floor
                                    // read wrong stays wrong for the whole server otherwise.
                                    .action { _, _ -> RoomTracker.reset() }
                                    .build(),
                            )
                            .option(
                                ButtonOption.createBuilder()
                                    .name(Component.translatable("austrianpainter.settings.unbind_room_types"))
                                    .description(
                                        OptionDescription.of(
                                            Component.translatable("austrianpainter.settings.unbind_room_types.desc"),
                                        ),
                                    )
                                    .action { _, _ ->
                                        PaintStorage.scope?.key?.let { ApSettings.bindRoomTypes(it, null) }
                                    }
                                    .build(),
                            )
                            .option(
                                ButtonOption.createBuilder()
                                    .name(Component.translatable("austrianpainter.settings.unbind_room_blocks"))
                                    .description(
                                        OptionDescription.of(
                                            Component.translatable("austrianpainter.settings.unbind_room_blocks.desc"),
                                        ),
                                    )
                                    .action { _, _ ->
                                        PaintStorage.scope?.let {
                                            if (it.isBoss) ApSettings.bindBossPreset(it.key, null)
                                            else ApSettings.bindRoomPreset(it.key, null)
                                        }
                                    }
                                    .build(),
                            )
                            .option(
                                ButtonOption.createBuilder()
                                    .name(Component.translatable("austrianpainter.settings.unbind_room_palettes"))
                                    .description(
                                        OptionDescription.of(
                                            Component.translatable("austrianpainter.settings.unbind_room_palettes.desc"),
                                        ),
                                    )
                                    .action { _, _ ->
                                        PaintStorage.scope?.key?.let { ApSettings.bindRoomPalettes(it, null) }
                                    }
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .category(
                ConfigCategory.createBuilder()
                    .name(Component.translatable("austrianpainter.settings.presets"))
                    .group(
                        OptionGroup.createBuilder()
                            .name(Component.translatable("austrianpainter.presets.blocks"))
                            .option(
                                Option.createBuilder<String>()
                                    .name(Component.translatable("austrianpainter.settings.active_preset"))
                                    .description(
                                        OptionDescription.of(
                                            Component.translatable("austrianpainter.settings.active_blocks.desc"),
                                        ),
                                    )
                                    .binding(
                                        PresetStores.blocks.activeName,
                                        { pendingBlockPreset },
                                        { pendingBlockPreset = it },
                                    )
                                    .controller {
                                        CyclingListControllerBuilder.create(it)
                                            .values(blockPresets)
                                            .formatValue { name -> Component.literal(name) }
                                    }
                                    .build(),
                            )
                            .option(
                                ButtonOption.createBuilder()
                                    .name(Component.translatable("austrianpainter.settings.manage_blocks"))
                                    .action { screen, _ ->
                                        PainterScreen.openPresets(screen)
                                    }
                                    .build(),
                            )
                            .build(),
                    )
                    .group(
                        OptionGroup.createBuilder()
                            .name(Component.translatable("austrianpainter.presets.types"))
                            .option(
                                Option.createBuilder<String>()
                                    .name(Component.translatable("austrianpainter.settings.active_preset"))
                                    .description(
                                        OptionDescription.of(
                                            Component.translatable("austrianpainter.settings.active_types.desc"),
                                        ),
                                    )
                                    .binding(
                                        PresetStores.types.activeName,
                                        { pendingTypePreset },
                                        { pendingTypePreset = it },
                                    )
                                    .controller {
                                        CyclingListControllerBuilder.create(it)
                                            .values(typePresets)
                                            .formatValue { name -> Component.literal(name) }
                                    }
                                    .build(),
                            )
                            .option(
                                ButtonOption.createBuilder()
                                    .name(Component.translatable("austrianpainter.settings.manage_types"))
                                    .action { screen, _ ->
                                        PainterScreen.openPresets(screen)
                                    }
                                    .build(),
                            )
                            .build(),
                    )
                    .group(
                        OptionGroup.createBuilder()
                            .name(Component.translatable("austrianpainter.presets.palettes"))
                            .option(
                                Option.createBuilder<String>()
                                    .name(Component.translatable("austrianpainter.settings.active_preset"))
                                    .description(
                                        OptionDescription.of(
                                            Component.translatable("austrianpainter.settings.active_palette.desc"),
                                        ),
                                    )
                                    .binding(
                                        PresetStores.palettes.activeName,
                                        { pendingPalette },
                                        { pendingPalette = it },
                                    )
                                    .controller {
                                        CyclingListControllerBuilder.create(it)
                                            .values(palettes)
                                            .formatValue { name -> Component.literal(name) }
                                    }
                                    .build(),
                            )
                            .option(
                                ButtonOption.createBuilder()
                                    .name(Component.translatable("austrianpainter.settings.manage_palettes"))
                                    .action { screen, _ ->
                                        PainterScreen.openPresets(screen)
                                    }
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .save {
                ApSettings.save()
                // Only swap when it actually changed; activating rebuilds every loaded chunk.
                if (pendingBlockPreset != PresetStores.blocks.activeName) {
                    PaintStorage.activateBlockPreset(pendingBlockPreset)
                }
                if (pendingTypePreset != PresetStores.types.activeName) {
                    PaintStorage.activateTypePreset(pendingTypePreset)
                }
                if (pendingPalette != PresetStores.palettes.activeName) {
                    PaintStorage.activatePalette(pendingPalette)
                }
            }
            .build()
            .generateScreen(parent)
    }
}
