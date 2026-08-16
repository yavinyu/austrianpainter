package com.maxisch.mixin.client.fluid;

import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Reads the baked model for every fluid, keyed by {@link Fluid}, out of {@link FluidStateModelSet}.
 *
 * {@code com.maxisch.client.render.ReplaceFluid} needs the counterpart fluid's plain model
 * when swapping water/lava, and this field is private with no getter. Reading it directly
 * avoids calling {@code FluidStateModelSet#get} again from inside the mixin that already
 * intercepts it - both swap directions can be enabled at once, and a recursive call would
 * re-enter the mixin and deadlock.
 */
@Mixin(FluidStateModelSet.class)
public interface FluidStateModelSetAccessor {

	@Accessor("modelByFluid")
	Map<Fluid, FluidModel> austrianpainter$modelByFluid();
}
