package com.maxisch.mixin.client;

import com.maxisch.client.gui.TooltipChrome;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws this mod's own tooltip background while one of its screens is open.
 *
 * Vanilla's purple-bordered box is the last piece of another design language left in the paint UI.
 * There is no per-tooltip hook to override - the background is drawn by one static call - so this
 * cancels that call and draws a card instead.
 *
 * <p>Deliberately narrow: {@link TooltipChrome#appliesTo()} gates on the open screen, so every other
 * tooltip in the game is untouched. A mod that restyles the whole game's tooltips because it wanted
 * nice ones on its own screen is a mod doing something it was not asked to do.
 */
@Mixin(TooltipRenderUtil.class)
public class TooltipBackgroundMixin {

	@Inject(method = "extractTooltipBackground", at = @At("HEAD"), cancellable = true)
	private static void austrianpainter$flatTooltipBackground(
			GuiGraphicsExtractor graphics,
			int x,
			int y,
			int width,
			int height,
			Identifier sprite,
			CallbackInfo ci
	) {
		if (!TooltipChrome.INSTANCE.appliesTo()) {
			return;
		}
		TooltipChrome.INSTANCE.draw(graphics, x, y, width, height);
		ci.cancel();
	}
}
