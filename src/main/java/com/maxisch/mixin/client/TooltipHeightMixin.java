package com.maxisch.mixin.client;

import com.maxisch.client.gui.Theme;
import com.maxisch.client.gui.TooltipChrome;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTextTooltip;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reports Josefin's real line height instead of vanilla's hardcoded 10px while one of this mod's
 * screens is open.
 *
 * <p>Vanilla's own tooltip layout sizes the background box and spaces every line from this one
 * number ({@code ClientTextTooltip.getHeight} returns a bare {@code 10}, decompiled from the
 * shipped class - not {@code font.lineHeight}). {@link TooltipTextMixin} already draws the line
 * itself through NanoVG at {@link Theme#TEXT_SIZE}, whose real vertical footprint the vanilla
 * constant was never tuned for, so the box vanilla computes comes up short. The "+6" here matches
 * the per-line drawing surface {@link TooltipChrome#drawText} already budgets for the same glyphs.
 */
@Mixin(ClientTextTooltip.class)
public class TooltipHeightMixin {

	@Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
	private void austrianpainter$josefinLineHeight(Font font, CallbackInfoReturnable<Integer> cir) {
		if (!TooltipChrome.INSTANCE.appliesTo()) {
			return;
		}
		int height = (int) Math.ceil(Theme.INSTANCE.textHeight(Theme.TEXT_SIZE, null)) + 6;
		cir.setReturnValue(Math.max(height, 10));
	}
}
