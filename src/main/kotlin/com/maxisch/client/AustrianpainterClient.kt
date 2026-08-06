package com.maxisch.client

import com.maxisch.client.render.AreaHighlight
import com.maxisch.client.render.PaintModelPlugin
import com.maxisch.paint.ApSettings
import com.maxisch.paint.PaintStorage
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

class AustrianpainterClient : ClientModInitializer {

    private var lastDimension: ResourceKey<Level>? = null

    override fun onInitializeClient() {
        // Settings decide which presets a world binds to, so they have to be up before any join.
        ApSettings.load()

        ModelLoadingPlugin.register(PaintModelPlugin)
        ckPaintKeys.register()
        PaintCommands.register()
        PaintHud.register()
        AreaHighlight.register()

        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            lastDimension = null
            PaintStorage.onJoinWorld()
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            lastDimension = null
            PaintSelection.reset()
            PaintArea.reset()
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
                if (dimension != null) PaintStorage.refreshIndex()
            }
            PaintStorage.tick()
        }
    }
}
