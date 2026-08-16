package com.maxisch.mixin.client.fluid;

import com.maxisch.client.render.model.ReplaceFluid;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets water/lava render as one another, and/or tinted, per the "Replace Fluid" settings.
 *
 * Injected at RETURN rather than HEAD so vanilla always computes the plain model for the
 * fluid actually being resolved first - the tint-only, no-swap case then needs no further
 * lookup at all, and only an actual swap needs {@link FluidStateModelSetAccessor}'s map read.
 */
@Mixin(FluidStateModelSet.class)
public abstract class FluidStateModelSetMixin {

	@Inject(method = "get", at = @At("RETURN"), cancellable = true)
	private void austrianpainter$replaceFluid(FluidState state, CallbackInfoReturnable<FluidModel> cir) {
		FluidStateModelSetAccessor accessor = (FluidStateModelSetAccessor) this;
		FluidModel overridden = ReplaceFluid.override(state.getType(), cir.getReturnValue(), accessor.austrianpainter$modelByFluid());
		if (overridden != null) {
			cir.setReturnValue(overridden);
		}
	}
}
