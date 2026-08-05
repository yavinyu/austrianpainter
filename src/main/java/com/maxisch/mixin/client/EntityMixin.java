package com.maxisch.mixin.client;

import com.maxisch.paint.PaintIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Step sounds resolve from the state being walked on, so repaint that state before the sound group
 * is read. Guarded to the client level so the integrated server keeps its own behaviour.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

	@ModifyVariable(method = "playStepSound", at = @At("HEAD"), argsOnly = true)
	private BlockState austrianpainter$repaintStepSound(BlockState state, BlockPos pos) {
		Entity self = (Entity) (Object) this;

		if (!self.level().isClientSide()) {
			return state;
		}

		Block painted = PaintIndex.soundPaintAt(pos, state);
		return painted == null ? state : painted.defaultBlockState();
	}
}
