package com.maxisch.mixin.client;

import com.maxisch.client.gui.TooltipChrome;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTextTooltip;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws tooltip text in the shipped font while one of this mod's screens is open.
 *
 * The background alone was not enough: a card-shaped tooltip full of bitmap glyphs still reads as
 * vanilla. There is no hook between the two - one method draws one line - so this cancels it and
 * draws the same line through NanoVG.
 *
 * <p>The line's width is still measured by vanilla, since {@code getWidth(Font)} is what sizes the
 * background before any of this runs. The two fonts agree to about 1% at this size, so the text sits
 * inside its box; a face with wider metrics would need that measurement intercepted as well.
 */
@Mixin(ClientTextTooltip.class)
public class TooltipTextMixin {

	@Shadow
	@Final
	private FormattedCharSequence text;

	@Inject(method = "extractText", at = @At("HEAD"), cancellable = true)
	private void austrianpainter$tooltipTextInShippedFont(
			GuiGraphicsExtractor graphics,
			Font font,
			int x,
			int y,
			CallbackInfo ci
	) {
		if (!TooltipChrome.INSTANCE.appliesTo()) {
			return;
		}

		// FormattedCharSequence carries styling per code point; the tooltips this mod raises are
		// plain, so it is flattened. Anything styled would lose its colour here, which is why the
		// gate above keeps this to our own screens.
		StringBuilder line = new StringBuilder();
		text.accept((index, style, codePoint) -> {
			line.appendCodePoint(codePoint);
			return true;
		});

		TooltipChrome.INSTANCE.drawText(graphics, line.toString(), x, y);
		ci.cancel();
	}
}
