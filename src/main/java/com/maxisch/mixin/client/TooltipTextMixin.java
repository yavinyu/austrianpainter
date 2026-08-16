package com.maxisch.mixin.client;

import com.maxisch.client.gui.Theme;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Draws tooltip text in the shipped font while one of this mod's screens is open, and reports its
 * real Josefin width for the box vanilla builds around it.
 *
 * The background alone was not enough: a card-shaped tooltip full of bitmap glyphs still reads as
 * vanilla. There is no hook between the two - one method draws one line - so this cancels it and
 * draws the same line through NanoVG.
 *
 * <p>{@code getWidth(Font)} is what sizes the background before any of this runs, and originally
 * this was left as vanilla's {@code font.width(text)} on the assumption the two faces agree closely
 * enough at this size. They do not always - a long word can sit wider in Josefin than in the bitmap
 * font by enough to spill past the box vanilla sized for it - so this is now measured with
 * {@link Theme#textWidth} too, the same way {@link TooltipHeightMixin} already does for height.
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
		TooltipChrome.INSTANCE.drawText(graphics, flatten(), x, y);
		ci.cancel();
	}

	@Inject(method = "getWidth", at = @At("HEAD"), cancellable = true)
	private void austrianpainter$josefinWidth(Font font, CallbackInfoReturnable<Integer> cir) {
		if (!TooltipChrome.INSTANCE.appliesTo()) {
			return;
		}
		int vanillaWidth = font.width(text);
		// +4 slack: NanoVG's measured advance width does not perfectly match what it rasterises, and
		// this number sizes the background box TooltipChrome.drawText's own (separately, more
		// generously padded) render surface draws over - undersizing this reads as text spilling
		// past the visible panel even once the glyphs themselves are no longer clipped.
		int josefinWidth = (int) Math.ceil(Theme.INSTANCE.textWidth(flatten(), Theme.TEXT_SIZE, null, 0f)) + 4;
		cir.setReturnValue(Math.max(vanillaWidth, josefinWidth));
	}

	// FormattedCharSequence carries styling per code point; the tooltips this mod raises are plain,
	// so it is flattened. Anything styled would lose its colour here, which is why every caller
	// gates on TooltipChrome.appliesTo() and keeps this to our own screens.
	private String flatten() {
		StringBuilder line = new StringBuilder();
		text.accept((index, style, codePoint) -> {
			line.appendCodePoint(codePoint);
			return true;
		});
		return line.toString();
	}
}
