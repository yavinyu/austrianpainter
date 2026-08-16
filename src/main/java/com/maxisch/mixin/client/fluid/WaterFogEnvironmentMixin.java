package com.maxisch.mixin.client.fluid;

import com.maxisch.client.render.model.ReplaceFluid;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.WaterFogEnvironment;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla water fog color comes from a per-biome environment attribute, and fog thickness from
 * biome-driven start/end distances - a "Replace Fluid" tint (color and opacity both) already
 * reaches the block mesh via {@link ReplaceFluid#override}, this is what makes it reach the
 * submersion fog too. See {@link LavaFogEnvironmentMixin} for why both mixins defer to
 * {@link ReplaceFluid#fogTintColor}/{@link ReplaceFluid#fogOpacity} unconditionally rather than
 * checking which fluid they are.
 */
@Mixin(WaterFogEnvironment.class)
public abstract class WaterFogEnvironmentMixin {

	private static final float NO_FOG_DISTANCE = 512f;

	@Inject(method = "getBaseColor", at = @At("RETURN"), cancellable = true)
	private void austrianpainter$tint(ClientLevel level, Camera camera, int i, float partialTicks, CallbackInfoReturnable<Integer> cir) {
		Integer tint = ReplaceFluid.fogTintColor();
		if (tint != null) {
			cir.setReturnValue(tint);
		}
	}

	@Inject(method = "setupFog", at = @At("RETURN"))
	private void austrianpainter$opacity(FogData fogData, Camera camera, ClientLevel level, float partialTicks, DeltaTracker deltaTracker, CallbackInfo ci) {
		Float opacity = ReplaceFluid.fogOpacity();
		if (opacity == null || opacity >= 1f) {
			return;
		}
		float t = 1f - opacity;
		fogData.environmentalStart = Mth.lerp(t, fogData.environmentalStart, NO_FOG_DISTANCE);
		fogData.environmentalEnd = Mth.lerp(t, fogData.environmentalEnd, NO_FOG_DISTANCE);
		fogData.skyEnd = fogData.environmentalEnd;
		fogData.cloudEnd = fogData.environmentalEnd;
	}
}
