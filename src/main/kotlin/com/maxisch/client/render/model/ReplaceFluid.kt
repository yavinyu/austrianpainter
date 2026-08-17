package com.maxisch.client.render.model

import com.maxisch.paint.settings.ApSettings
import net.minecraft.client.color.block.BlockTintSources
import net.minecraft.client.renderer.block.FluidModel
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.tags.FluidTags
import net.minecraft.tags.TagKey
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.Fluids

/**
 * Decides whether water/lava should render as the other fluid and/or tinted, per
 * [ApSettings]'s "Replace Fluid" settings. Called from the [FluidStateModelSet] mixin -
 * mirrors [RetexturePalette]'s role as a pure render-decision object with no dependency on
 * [com.maxisch.paint.PaintIndex]/rules, since a fluid target is a colour, not a `Block`.
 *
 * The block mesh isn't the only thing that needs to agree with these settings: while the camera
 * is actually inside the fluid, vanilla's fog ([Camera.getFluidInCamera], `LavaFogEnvironment`,
 * `WaterFogEnvironment`) reads the real, un-swapped [FluidState]/[FluidTags] - so [CameraMixin],
 * `LavaFogEnvironmentMixin` and `WaterFogEnvironmentMixin` call into this object too, via
 * [apparentFluidTagMatches] and [fogTintColor].
 */
object ReplaceFluid {

    /** The other fluid in a swap pair, keeping still/flowing variants matched up. Null for
     *  every fluid that isn't water or lava - this feature only ever touches those two. */
    private fun counterpart(fluid: Fluid): Fluid? = when (fluid) {
        Fluids.WATER -> Fluids.LAVA
        Fluids.FLOWING_WATER -> Fluids.FLOWING_LAVA
        Fluids.LAVA -> Fluids.WATER
        Fluids.FLOWING_LAVA -> Fluids.FLOWING_WATER
        else -> null
    }

    private enum class RealFluid { WATER, LAVA, OTHER }

    private fun classify(fluid: Fluid): RealFluid = when (fluid) {
        Fluids.WATER, Fluids.FLOWING_WATER -> RealFluid.WATER
        Fluids.LAVA, Fluids.FLOWING_LAVA -> RealFluid.LAVA
        else -> RealFluid.OTHER
    }

    /** The real fluid last found to match the apparent tag [Camera.getFluidInCamera] settled
     *  on - i.e. whichever fluid is actually under the camera, regardless of how it's being
     *  swapped to look. Read by the fog-color mixins so tint stays keyed to the real material,
     *  same as [override] does for the block mesh. Client is single-threaded per frame, so a
     *  plain field (not thread-local) is enough. */
    private var lastRealFluid: RealFluid = RealFluid.OTHER

    /**
     * Called from [CameraMixin] in place of `FluidState.is(TagKey)` inside `getFluidInCamera`,
     * for both the water and the lava tag check it makes. Reinterprets a real water/lava tag
     * match according to the active swap, so the fog system's [net.minecraft.world.level.material.FogType]
     * pick (and the FOV squeeze that reuses it) matches what the block mesh already shows.
     * Anything that isn't water or lava falls through to [realResult] untouched.
     */
    @JvmStatic
    fun apparentFluidTagMatches(fluid: Fluid, tag: TagKey<Fluid>, realResult: Boolean): Boolean {
        val real = classify(fluid)
        if (real == RealFluid.OTHER) return realResult

        val apparentWater = (real == RealFluid.WATER && !ApSettings.effectiveWaterAsLava) ||
            (real == RealFluid.LAVA && ApSettings.effectiveLavaAsWater)
        val matches = when (tag) {
            FluidTags.WATER -> apparentWater
            FluidTags.LAVA -> !apparentWater
            else -> return realResult
        }
        if (matches) lastRealFluid = real
        return matches
    }

    /** Read by `LavaFogEnvironmentMixin`/`WaterFogEnvironmentMixin` to override the hardcoded/
     *  biome fog color once [apparentFluidTagMatches] has settled which real fluid the camera is
     *  in. Null leaves vanilla's fog color untouched (no tint configured for that fluid). */
    @JvmStatic
    fun fogTintColor(): Int? = when (lastRealFluid) {
        RealFluid.WATER -> ApSettings.effectiveWaterTintColor.takeIf { ApSettings.effectiveWaterTintEnabled }
        RealFluid.LAVA -> ApSettings.effectiveLavaTintColor.takeIf { ApSettings.effectiveLavaTintEnabled }
        RealFluid.OTHER -> null
    }

    /** Read by `LavaFogEnvironmentMixin`/`WaterFogEnvironmentMixin`'s `setupFog` injector.
     *  Vanilla fog color is RGB-only - [fogTintColor]'s alpha byte never reaches the screen on
     *  its own, so it's reused here as fog thickness instead, keeping the "how much does the
     *  tint show through" feel consistent between the translucent block mesh and the fog. Null
     *  (no tint configured) leaves vanilla's fog distances untouched. */
    @JvmStatic
    fun fogOpacity(): Float? = fogTintColor()?.let { (it ushr 24 and 0xFF) / 255f }

    /** Null leaves [vanilla] untouched. [modelByFluid] is read directly (never through
     *  `FluidStateModelSet.get()` again) so both swap directions can be enabled at once
     *  without the mixin re-entering itself. */
    @JvmStatic
    fun override(fluid: Fluid, vanilla: FluidModel, modelByFluid: Map<Fluid, FluidModel>): FluidModel? {
        val other = counterpart(fluid) ?: return null
        val isWater = fluid === Fluids.WATER || fluid === Fluids.FLOWING_WATER
        val swap = if (isWater) ApSettings.effectiveWaterAsLava else ApSettings.effectiveLavaAsWater
        val tintEnabled = if (isWater) ApSettings.effectiveWaterTintEnabled else ApSettings.effectiveLavaTintEnabled
        if (!swap && !tintEnabled) return null

        val base = if (swap) modelByFluid[other] ?: vanilla else vanilla
        if (!tintEnabled) return if (swap) base else null

        val tintColor = if (isWater) ApSettings.effectiveWaterTintColor else ApSettings.effectiveLavaTintColor
        val flat = if (isWater) ApSettings.effectiveWaterTintFlat else ApSettings.effectiveLavaTintFlat

        // Tint is a GPU multiply against the sprite, not a color override - so it only reads as
        // the exact picked color when the sprite is near-neutral, which is true of vanilla's pale
        // water_still.png by accident and false of vanilla's saturated lava_still.png and of most
        // resource packs that bake real color into their fluid textures. "Flat" mode swaps in this
        // mod's own solid-white sprite so the multiply always lands on the picked color exactly,
        // trading away the sprite's wave/flow texture to do it.
        val flatMaterial = if (flat) PaintRenderSupport.flatTintMaterial() else null
        val stillMaterial = flatMaterial ?: base.stillMaterial()
        val flowingMaterial = flatMaterial ?: base.flowingMaterial()
        val overlayMaterial = flatMaterial ?: base.overlayMaterial()

        return FluidModel(
            // Lava's vanilla SOLID layer never blends alpha, so opacity would silently do
            // nothing unless tinting always forces a blended layer.
            ChunkSectionLayer.TRANSLUCENT,
            stillMaterial,
            flowingMaterial,
            overlayMaterial,
            BlockTintSources.constant(tintColor),
        )
    }
}
