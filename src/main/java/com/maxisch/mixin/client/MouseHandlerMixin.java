package com.maxisch.mixin.client;

import com.maxisch.client.PaintKeys;
import com.maxisch.paint.session.PaintBrush;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Scrolling while the brush key is held resizes the brush instead of changing the hotbar slot.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void austrianpainter$resizeBrush(long window, double xOffset, double yOffset, CallbackInfo ci) {
		if (yOffset == 0 || !PaintKeys.isBrushKeyDown()) {
			return;
		}

		// Key mappings report released while a screen is open, so isBrushKeyDown already excludes
		// that case; only the in-world check is left.
		if (Minecraft.getInstance().level == null) {
			return;
		}

		PaintBrush.adjustRadius(yOffset > 0 ? 1 : -1);
		ci.cancel();
	}
}
