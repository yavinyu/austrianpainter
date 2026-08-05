package com.maxisch.client

import com.maxisch.client.render.PaintModelPlugin
import com.maxisch.paint.PaintStorage
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

class AustrianpainterClient : ClientModInitializer {

    private var lastDimension: ResourceKey<Level>? = null

    override fun onInitializeClient() {
        ModelLoadingPlugin.register(PaintModelPlugin)
        PaintKeys.register()
        PaintCommands.register()
        PaintHud.register()

        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            lastDimension = null
            PaintStorage.onJoinWorld()
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            lastDimension = null
            PaintSelection.reset()
            PaintStorage.onLeaveWorld()
        }

        // Rules are per dimension, so the index has to follow the player through portals.
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val dimension = client.level?.dimension()
            if (dimension != lastDimension) {
                lastDimension = dimension
                if (dimension != null) PaintStorage.refreshIndex()
            }
        }
    }
}
