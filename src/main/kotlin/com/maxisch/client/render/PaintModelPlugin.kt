package com.maxisch.client.render

import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier

/** Wraps every baked block model so any of them can be repainted at render time. */
object PaintModelPlugin : ModelLoadingPlugin {

    override fun initialize(context: ModelLoadingPlugin.Context) {
        // Models are being rebaked, so anything derived from the previous bake is stale.
        PaintRenderSupport.invalidate()

        context.modifyBlockModelAfterBake().register(ModelModifier.WRAP_PHASE) { model, _ ->
            PaintedBlockStateModel(model)
        }
    }
}
