package com.maxisch.mixin.client.fluid;

import com.maxisch.client.render.model.ReplaceFluid;
import net.minecraft.client.Camera;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lets the submersion fog system agree with the "Replace Fluid" swap. {@code getFluidInCamera}
 * reads the real, un-swapped {@link FluidState}/tag directly - it never goes through the
 * {@code FluidStateModelSet} mixin - so without this, lava rendered as water (or vice versa)
 * still fogs and FOV-squeezes like the real fluid the instant the camera enters it.
 *
 * Redirecting both tag checks the method makes (water, then lava) lets
 * {@link ReplaceFluid#apparentFluidTagMatches} reinterpret each one from a single place.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

	@Redirect(
		method = "getFluidInCamera",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FluidState;is(Lnet/minecraft/tags/TagKey;)Z")
	)
	private boolean austrianpainter$apparentFluidTag(FluidState state, TagKey<Fluid> tag) {
		return ReplaceFluid.apparentFluidTagMatches(state.getType(), tag, state.is(tag));
	}
}
