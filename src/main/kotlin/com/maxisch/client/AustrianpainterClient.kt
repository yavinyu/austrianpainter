package com.maxisch.client

import com.maxisch.client.render.AreaHighlight
import com.maxisch.client.render.BlockHighlight
import com.maxisch.client.render.PaintHud
import com.maxisch.client.render.PaintedOverlay
import com.maxisch.client.render.PaintModelPlugin
import com.maxisch.dungeon.DeviceColumnData
import com.maxisch.dungeon.RoomDataStore
import com.maxisch.dungeon.RoomTracker
import com.maxisch.paint.ApSettings
import com.maxisch.paint.PaintStorage
import com.maxisch.paint.PresetStores
import com.maxisch.paint.session.PaintArea
import com.maxisch.paint.session.PaintSelection
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

class AustrianpainterClient : ClientModInitializer {

    private var lastDimension: ResourceKey<Level>? = null

    override fun onInitializeClient() {
        // Settings decide which presets a world binds to, so they have to be up before any join.
        ApSettings.load()
        // Palettes and rulesets are not world-bound, so they load once here rather than on join.
        PresetStores.palettes.load(ApSettings.activePalette)
        PresetStores.rulesets.load(ApSettings.activeRuleset)
        // Reads the cached room list now and refreshes it in the background.
        RoomDataStore.start()
        // Bundled, unlike the room list, so this is a few KB off the classloader and needs no thread.
        DeviceColumnData.load()

        ModelLoadingPlugin.register(PaintModelPlugin)
        PaintKeys.register()
        PaintCommands.register()
        PaintHud.register()
        AreaHighlight.register()
        BlockHighlight.register()
        PaintedOverlay.register()

        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            lastDimension = null
            // Hypixel sends the player to a fresh instance per dungeon, so the layout never carries
            // over a join.
            RoomTracker.reset()
            PaintStorage.onJoinWorld()
            sendIntroOnce(client)
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            lastDimension = null
            PaintSelection.reset()
            PaintArea.reset()
            RoomTracker.reset()
            BlockHighlight.clear()
            PaintStorage.onLeaveWorld()
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register {
            PaintStorage.flush()
            ApSettings.save()
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            // Presets are per dimension on the positional side, so the index follows the player
            // through portals.
            val dimension = client.level?.dimension()
            if (dimension != lastDimension) {
                lastDimension = dimension
                // On Hypixel a dimension change means a different server instance, so neither the
                // detected floor nor the scanned layout can carry over.
                RoomTracker.reset()
                if (dimension != null) PaintStorage.refreshIndex()
            }
            RoomTracker.tick()
            PaintStorage.tick()
        }
    }

    /**
     * The mod has no presence in the world until a key is pressed, so a first-time player has no
     * way to find it short of reading the controls screen. One line, once, ever.
     */
    private fun sendIntroOnce(client: Minecraft) {
        if (ApSettings.seenIntro) return
        ApSettings.seenIntro = true
        ApSettings.save()
        client.player?.sendSystemMessage(
            Component.translatable(
                "austrianpainter.intro",
                KeyHints.name(PaintKeys.openMenuKey),
                KeyHints.name(PaintKeys.openAreaKey),
            ),
        )
    }
}
