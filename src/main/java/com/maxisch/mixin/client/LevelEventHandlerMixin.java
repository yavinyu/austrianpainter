package com.maxisch.mixin.client;

import com.maxisch.paint.PaintIndex;
import net.minecraft.client.renderer.LevelEventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Level event 2001 carries the broken block's state id and drives both the break sound and the
 * destroy particles. Swapping the id here repaints both, and only for the local client.
 */
@Mixin(LevelEventHandler.class)
public abstract class LevelEventHandlerMixin {

	private static final int PARTICLES_DESTROY_BLOCK = 2001;

	@ModifyVariable(method = "levelEvent", at = @At("HEAD"), argsOnly = true, index = 3)
	private int austrianpainter$repaintDestroyedBlock(int data, int type, BlockPos pos) {
		if (type != PARTICLES_DESTROY_BLOCK) {
			return data;
		}

		BlockState state = Block.stateById(data);
		Block painted = PaintIndex.soundPaintAt(pos, state);

		if (painted == null) {
			return data;
		}

		return Block.getId(painted.defaultBlockState());
	}
}
