package com.maxisch.mixin.client.paint;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.maxisch.client.render.model.RetexturePalette;
import com.maxisch.paint.PaintIndex;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A block that reports {@code RenderShape.INVISIBLE} - barrier, structure void, light - never
 * reaches the wrapped block-state model at all: {@code SectionCompiler.compile} skips model
 * dispatch for it before the model is ever asked for quads. Reporting {@code MODEL} instead for
 * exactly the positions a usable donor is painted onto lets the wrapper's synthesized-cube path
 * (see {@code PaintedBlockStateModel}) run like it would for any other target.
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {

	@ModifyExpressionValue(
		method = "compile",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;"
		)
	)
	private RenderShape austrianpainter$paintedRenderShape(
		RenderShape original,
		@Local(index = 16) BlockPos pos,
		@Local(index = 17) BlockState state
	) {
		if (original != RenderShape.INVISIBLE) {
			return original;
		}

		Block paint = PaintIndex.paintAt(pos, state);
		if (paint == null) {
			return original;
		}

		return RetexturePalette.of(paint).getUsable() ? RenderShape.MODEL : original;
	}
}
