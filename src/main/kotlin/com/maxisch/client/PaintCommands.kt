package com.maxisch.client

import com.maxisch.client.render.CullDiagnostics
import com.maxisch.dungeon.DeviceColumnData
import com.maxisch.dungeon.DungeonLocation
import com.maxisch.dungeon.RoomDataStore
import com.maxisch.paint.ApSettings
import com.maxisch.paint.DeviceArray
import com.maxisch.paint.DeviceColumns
import com.maxisch.paint.DeviceSource
import com.maxisch.paint.PaintStorage
import com.maxisch.paint.displayName
import com.maxisch.paint.session.PaintBrush
import com.maxisch.paint.session.PaintSelection
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.IdentifierArgument
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/** Client-only `/paintbrush` command. Nothing here reaches the server. */
object PaintCommands {

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal("paintbrush")
                    .executes { context -> status(context.source) }
                    .then(
                        ClientCommands.literal("on").executes { context ->
                            PaintBrush.enabled = true
                            status(context.source)
                        },
                    )
                    .then(
                        ClientCommands.literal("off").executes { context ->
                            PaintBrush.enabled = false
                            feedback(context.source, "austrianpainter.brush.off")
                        },
                    )
                    .then(
                        ClientCommands.literal("toggle").executes { context ->
                            PaintBrush.enabled = !PaintBrush.enabled
                            status(context.source)
                        },
                    )
                    .then(
                        ClientCommands.literal("donor").then(
                            ClientCommands.argument("block", IdentifierArgument.id())
                                .suggests { _, builder ->
                                    SharedSuggestionProvider.suggestResource(
                                        BuiltInRegistries.BLOCK.keySet(),
                                        builder,
                                    )
                                }
                                .executes { context -> setDonor(context) },
                        ),
                    )
                    .then(
                        ClientCommands.literal("radius").then(
                            ClientCommands.argument(
                                "size",
                                IntegerArgumentType.integer(PaintBrush.MIN_RADIUS, PaintBrush.MAX_RADIUS),
                            ).executes { context ->
                                PaintBrush.radius = IntegerArgumentType.getInteger(context, "size")
                                ApSettings.save()
                                feedback(
                                    context.source,
                                    "austrianpainter.brush.radius",
                                    PaintBrush.radius,
                                )
                            },
                        ),
                    )
                    .then(
                        ClientCommands.literal("room")
                            .executes { context -> roomStatus(context.source) }
                            .then(
                                ClientCommands.literal("raw").executes { context ->
                                    dumpRawSidebar(context.source)
                                },
                            ),
                    )
                    .then(
                        ClientCommands.literal("device")
                            .executes { context -> deviceStatus(context.source) }
                            .then(
                                ClientCommands.literal("on").executes { context ->
                                    setDeviceEnabled(context.source, true)
                                },
                            )
                            .then(
                                ClientCommands.literal("off").executes { context ->
                                    setDeviceEnabled(context.source, false)
                                },
                            )
                            .then(
                                ClientCommands.literal("nearest").executes { context ->
                                    deviceNearest(context.source)
                                },
                            )
                            .then(
                                ClientCommands.literal("probe").executes { context ->
                                    deviceProbe(context.source)
                                },
                            ),
                    )
                    .then(
                        ClientCommands.literal("undo").executes { context ->
                            context.source.sendFeedback(PaintStorage.undo())
                            1
                        },
                    )
                    .then(
                        ClientCommands.literal("redo").executes { context ->
                            context.source.sendFeedback(PaintStorage.redo())
                            1
                        },
                    )
                    .then(
                        ClientCommands.literal("cull").executes { context ->
                            context.source.sendFeedback(Component.literal(CullDiagnostics.summary()))
                            1
                        }.then(
                            ClientCommands.literal("reset").executes { context ->
                                CullDiagnostics.reset()
                                context.source.sendFeedback(Component.literal("Culling counters reset"))
                                1
                            },
                        ),
                    )
                    .then(
                        ClientCommands.literal("dungeon").then(
                            ClientCommands.argument("floor", StringArgumentType.word())
                                .suggests { _, builder ->
                                    SharedSuggestionProvider.suggest(FORCE_SUGGESTIONS, builder)
                                }
                                .executes { context -> forceDungeon(context, boss = false) }
                                .then(
                                    ClientCommands.literal("boss")
                                        .executes { context -> forceDungeon(context, boss = true) },
                                ),
                        ),
                    )
                    .then(
                        ClientCommands.literal("sound").then(
                            ClientCommands.argument("enabled", BoolArgumentType.bool())
                                .executes { context ->
                                    ApSettings.paintSound = BoolArgumentType.getBool(context, "enabled")
                                    ApSettings.save()
                                    feedback(
                                        context.source,
                                        if (ApSettings.paintSound) {
                                            "austrianpainter.sound.on"
                                        } else {
                                            "austrianpainter.sound.off"
                                        },
                                    )
                                },
                        ),
                    )
                    .then(
                        ClientCommands.literal("keys")
                            .executes { context -> keysStatus(context.source) }
                            .then(
                                ClientCommands.literal("on").executes { context ->
                                    setKeybindsEnabled(context.source, true)
                                },
                            )
                            .then(
                                ClientCommands.literal("off").executes { context ->
                                    setKeybindsEnabled(context.source, false)
                                },
                            )
                            .then(
                                ClientCommands.literal("toggle").executes { context ->
                                    setKeybindsEnabled(context.source, !ApSettings.keybindsEnabled)
                                },
                            ),
                    ),
            )
        }
    }

    private fun setDonor(context: CommandContext<FabricClientCommandSource>): Int {
        val id = context.getArgument("block", Identifier::class.java)
        val block = BuiltInRegistries.BLOCK.getValue(id)

        if (block == Blocks.AIR) {
            context.source.sendError(Component.translatable("austrianpainter.brush.unknown_block", id.toString()))
            return 0
        }

        PaintBrush.donor = block
        PaintBrush.enabled = true
        return status(context.source)
    }

    private val FORCE_SUGGESTIONS =
        listOf("off") + (1..7).map { "F$it" } + (1..7).map { "M$it" }

    /**
     * Lets a server that does not send Hypixel's sidebar - a test or simulation server - be treated
     * as a dungeon floor, so boss room paint can be authored there. Session-only on purpose.
     */
    private fun forceDungeon(context: CommandContext<FabricClientCommandSource>, boss: Boolean): Int {
        val raw = StringArgumentType.getString(context, "floor")

        if (raw.equals("off", ignoreCase = true)) {
            DungeonLocation.force(null)
            return feedback(context.source, "austrianpainter.room.force_off")
        }

        val floor = raw.uppercase()
        if (!Regex("^[FM][1-7]$").matches(floor)) {
            context.source.sendError(Component.translatable("austrianpainter.room.force_bad", raw))
            return 0
        }

        DungeonLocation.force(floor, boss)
        return feedback(
            context.source,
            if (boss) "austrianpainter.room.force_boss" else "austrianpainter.room.force_floor",
            floor,
        )
    }

    /** Dumps exactly what the client's scoreboard model holds, to diagnose a detection failure. */
    private fun dumpRawSidebar(source: FabricClientCommandSource): Int {
        for (line in DungeonLocation.debugSidebar()) {
            source.sendFeedback(Component.literal(line))
        }
        return 1
    }

    /** Diagnostics for the dungeon scope: without this the detection is invisible until it fails. */
    private fun roomStatus(source: FabricClientCommandSource): Int {
        feedback(
            source,
            "austrianpainter.room.data",
            RoomDataStore.byCore.size,
        )

        if (DungeonLocation.forced) {
            // A command name is not rebindable, so there is nothing to substitute - but making it
            // one click instead of one retype is the actual win here.
            source.sendFeedback(
                Component.translatable(
                    "austrianpainter.room.forced",
                    DungeonLocation.forcedFloor ?: "?",
                ).withStyle { style ->
                    style.withUnderlined(true)
                        .withClickEvent(ClickEvent.SuggestCommand("/paintbrush dungeon off"))
                },
            )
        }

        if (!DungeonLocation.inDungeon) {
            return feedback(source, "austrianpainter.room.no_dungeon")
        }

        feedback(
            source,
            "austrianpainter.room.floor",
            DungeonLocation.floor ?: "?",
            Component.translatable(
                if (DungeonLocation.inBoss) "austrianpainter.room.in_boss" else "austrianpainter.room.in_rooms",
            ),
        )

        // Says whether the sidebar is still being read, so "the floor went stale" and "the floor was
        // never found" can be told apart from chat. Meaningless while forced, which already said so.
        if (!DungeonLocation.forced) {
            feedback(
                source,
                if (DungeonLocation.latched) "austrianpainter.room.latched" else "austrianpainter.room.detecting",
            )
        }

        val scope = PaintStorage.scope
            ?: return feedback(source, "austrianpainter.room.unresolved")

        return feedback(
            source,
            "austrianpainter.room.scope",
            scope.key,
            "${scope.origin.x}, ${scope.origin.z}",
            scope.rotation,
            PaintStorage.positionsByDonor().values.sum(),
        )
    }

    // ---------------------------------------------------------------- device columns

    private fun setDeviceEnabled(source: FabricClientCommandSource, enabled: Boolean): Int {
        ApSettings.deviceEnabled = enabled
        ApSettings.save()
        DeviceColumns.invalidate()
        PaintStorage.onDeviceScopeChanged()
        return deviceStatus(source)
    }

    /**
     * Without this the layer is invisible until it fails: the pillars only exist on one floor of
     * one server, so "nothing happened" has half a dozen equally plausible causes.
     */
    private fun deviceStatus(source: FabricClientCommandSource): Int {
        if (!DeviceColumnData.loaded) return feedback(source, "austrianpainter.device.no_data")

        feedback(
            source,
            "austrianpainter.device.status",
            Component.translatable(
                if (ApSettings.deviceEnabled) "austrianpainter.device.on" else "austrianpainter.device.off",
            ),
            DeviceColumnData.columnCount,
            DeviceColumns.ruleCount(),
        )

        feedback(
            source,
            if (DeviceColumns.shouldApply()) {
                "austrianpainter.device.active"
            } else {
                "austrianpainter.device.inactive"
            },
        )

        for (array in DeviceArray.entries) {
            for (deviceSource in DeviceSource.entries) {
                val target = ApSettings.deviceRule(array, deviceSource)
                source.sendFeedback(
                    if (target == null) {
                        Component.translatable(
                            "austrianpainter.device.row_off",
                            arrayName(array),
                            deviceSource.block.name,
                        )
                    } else {
                        Component.translatable(
                            "austrianpainter.device.row",
                            arrayName(array),
                            deviceSource.block.name,
                            target.displayName(),
                        )
                    },
                )
            }
        }
        return 1
    }

    /** Finds a column to stand at without having to open the bundled coordinate file. */
    private fun deviceNearest(source: FabricClientCommandSource): Int {
        val player = source.player
        val column = DeviceColumns.nearestColumn(player.blockX, player.blockZ)
            ?: return feedback(source, "austrianpainter.device.no_data")

        source.sendFeedback(
            Component.translatable(
                "austrianpainter.device.nearest",
                column.base.x,
                column.base.z,
                arrayName(column.array),
                column.minY,
                column.maxY,
            ).withStyle { style ->
                style.withUnderlined(true).withClickEvent(
                    ClickEvent.SuggestCommand("/tp ${column.base.x} ${column.minY} ${column.base.z}"),
                )
            },
        )
        return 1
    }

    /**
     * Answers "is my rule wrong, is my band wrong, or is that block simply not diorite" in one
     * line, which is otherwise three separate guesses.
     */
    private fun deviceProbe(source: FabricClientCommandSource): Int {
        val pos = PaintSelection.lookedAtPos()
            ?: return feedback(source, "austrianpainter.brush.miss")
        val block = PaintSelection.lookedAtBlock()
            ?: return feedback(source, "austrianpainter.brush.miss")

        val column = DeviceColumns.columnAt(pos.x, pos.z)
            ?: return feedback(source, "austrianpainter.device.probe_miss")

        val deviceSource = DeviceColumns.sourceFor(block)
        val donor = if (deviceSource == null || pos.y < column.minY || pos.y > column.maxY) {
            null
        } else {
            DeviceColumns.donorFor(column.array, deviceSource, pos.x, pos.y, pos.z)
        }

        return feedback(
            source,
            if (donor == null) "austrianpainter.device.probe_plain" else "austrianpainter.device.probe_hit",
            arrayName(column.array),
            column.minY,
            column.maxY,
            block.name,
            donor?.name ?: Component.empty(),
        )
    }

    private fun arrayName(array: DeviceArray): Component =
        Component.translatable("austrianpainter.device.array.${array.key}")

    private fun setKeybindsEnabled(source: FabricClientCommandSource, enabled: Boolean): Int {
        ApSettings.keybindsEnabled = enabled
        ApSettings.save()
        return keysStatus(source)
    }

    private fun keysStatus(source: FabricClientCommandSource): Int {
        return feedback(
            source,
            if (ApSettings.keybindsEnabled) "austrianpainter.keys.on" else "austrianpainter.keys.off",
        )
    }

    private fun status(source: FabricClientCommandSource): Int {
        val donor: Block? = PaintBrush.donor
        source.sendFeedback(
            Component.translatable(
                "austrianpainter.brush.status",
                Component.translatable(
                    if (PaintBrush.enabled) "austrianpainter.brush.on" else "austrianpainter.brush.off",
                ),
                donor?.name ?: Component.translatable("austrianpainter.brush.no_donor"),
                PaintBrush.radius,
            ),
        )
        return 1
    }

    private fun feedback(source: FabricClientCommandSource, key: String, vararg args: Any): Int {
        source.sendFeedback(Component.translatable(key, *args))
        return 1
    }
}
