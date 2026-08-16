package com.maxisch.client

import com.maxisch.client.render.culling.CullDiagnostics
import com.maxisch.dungeon.detect.DungeonLocation
import com.maxisch.dungeon.room.RoomDataStore
import com.maxisch.paint.settings.ApSettings
import com.maxisch.paint.PaintStorage
import com.maxisch.paint.session.PaintBrush
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
import com.maxisch.client.keybind.PaintKeys

/** Client-only `/ap` command. Nothing here reaches the server. */
object PaintCommands {

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal("ap")
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
                        ClientCommands.literal("sound").executes { context ->
                            setSoundEnabled(context.source, !ApSettings.paintSound)
                        },
                    )
                    .then(
                        ClientCommands.literal("keys").executes { context ->
                            setKeybindsEnabled(context.source, !ApSettings.keybindsEnabled)
                        },
                    )
                    .then(
                        ClientCommands.literal("hud").executes { context ->
                            setHudEnabled(context.source, !ApSettings.showHud)
                        },
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
                        .withClickEvent(ClickEvent.SuggestCommand("/ap dungeon off"))
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

    private fun setSoundEnabled(source: FabricClientCommandSource, enabled: Boolean): Int {
        ApSettings.paintSound = enabled
        ApSettings.save()
        return feedback(
            source,
            if (ApSettings.paintSound) "austrianpainter.sound.on" else "austrianpainter.sound.off",
        )
    }

    private fun setKeybindsEnabled(source: FabricClientCommandSource, enabled: Boolean): Int {
        PaintKeys.setEnabled(enabled)
        return keysStatus(source)
    }

    private fun keysStatus(source: FabricClientCommandSource): Int {
        return feedback(
            source,
            if (ApSettings.keybindsEnabled) "austrianpainter.keys.on" else "austrianpainter.keys.off",
        )
    }

    private fun setHudEnabled(source: FabricClientCommandSource, enabled: Boolean): Int {
        ApSettings.showHud = enabled
        ApSettings.save()
        return hudStatus(source)
    }

    private fun hudStatus(source: FabricClientCommandSource): Int {
        return feedback(
            source,
            if (ApSettings.showHud) "austrianpainter.hud.on" else "austrianpainter.hud.off",
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
