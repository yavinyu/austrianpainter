package com.maxisch.client.gui

import com.maxisch.client.PaintBrush
import com.maxisch.paint.ApSettings
import com.maxisch.paint.PaintStorage
import com.maxisch.paint.PresetStores
import dev.isxander.yacl3.api.ButtonOption
import dev.isxander.yacl3.api.ConfigCategory
import dev.isxander.yacl3.api.Option
import dev.isxander.yacl3.api.OptionDescription
import dev.isxander.yacl3.api.OptionGroup
import dev.isxander.yacl3.api.YetAnotherConfigLib
import dev.isxander.yacl3.api.controller.CyclingListControllerBuilder
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** The YACL settings screen. Preset creation and deletion live in [PresetScreen]. */
object ApSettingsScreen {

    fun create(parent: Screen?): Screen {
        // Snapshot the names now so the cyclers have a stable value set for this screen's lifetime.
        val blockPresets = PresetStores.blocks.listWithActive()
        val typePresets = PresetStores.types.listWithActive()

        var pendingBlockPreset = PresetStores.blocks.activeName
        var pendingTypePreset = PresetStores.types.activeName

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
                                            Component.translatable("austrianpainter.settings.brush_radius.desc"),
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
                                        Minecraft.getInstance().setScreenAndShow(PresetScreen.blocks(screen))
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
                                        Minecraft.getInstance().setScreenAndShow(PresetScreen.types(screen))
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
            }
            .build()
            .generateScreen(parent)
    }
}
