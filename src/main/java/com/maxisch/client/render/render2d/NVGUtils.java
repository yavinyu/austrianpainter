/*
 * Original code (some functions) Copyright (c) 2026, odtheking
 * https://github.com/odtheking/OdinFabric/blob/main/src/main/kotlin/com/odtheking/odin/utils/ui/rendering/NVGRenderer.kt
 * Modified/added functions Copyright (c) 2026, rice.who - https://github.com/rs-mod/rsm
 *
 * Modified for Austrian Painter:
 *   - Lombok removed (@UtilityClass, @Getter, @AllArgsConstructor written out by hand)
 *   - image, GIF and Pair support dropped; this mod draws no images through NanoVG
 *   - NanoVG context and font creation made lazy, so neither runs during the Minecraft constructor
 *   - font set trimmed to the two shipped faces and moved to the austrianpainter namespace
 *   - RSMConfig.getStandardGuiScale() replaced by guiScale(), which reports Minecraft's own scale
 *   - a failed context or font load now disables drawing instead of killing the client
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.maxisch.client.render.render2d;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static java.lang.Math.round;
import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.nanovg.NanoVGGL3.*;

/**
 * The NanoVG drawing surface the paint UI's own widgets render through.
 *
 * <p>Coordinates are Minecraft GUI pixels: {@link com.maxisch.client.render.render2d.NvgBridge} applies the
 * scale and origin translate before handing control over, so a widget passes its own {@code x}/{@code y}
 * straight in. Nothing here is safe to call outside a {@link NvgBridge#draw} block - every method
 * assumes an open NanoVG frame.
 *
 * <p>Everything is static and the context is process-lifetime. The mallocs below are deliberately
 * never freed: they are singletons whose lifetime is the game's.
 */
public final class NVGUtils {

	private static final Logger LOGGER = LoggerFactory.getLogger("austrianpainter/nvg");

	public static final String JOSEFIN = "JoseFin";
	public static final String JOSEFIN_BOLD = "JoseFin Bold";

	private static final Map<NvgFont, NVGFontHandle> fontMap = new HashMap<>();
	private static final Map<String, NvgFont> registeredFonts = new HashMap<>();

	private static final Colour TEXT_SHADOW = Colour.of(0xFF000000);

	// Allocated on first use rather than in a static initialiser, so that a class-load caused by
	// something harmless (a constant read, say) cannot allocate native memory off the render thread.
	private static NVGPaint nvgPaint;
	private static NVGColor nvgColor;
	private static NVGColor nvgColor2;
	private static float[] fontBounds;

	private static Scissor scissor = null;
	private static boolean drawing = false;

	private static long vg = 0L;
	private static boolean initialised = false;
	/** Set when the context or the fonts could not be created; every draw then becomes a no-op. */
	private static boolean disabled = false;

	private NVGUtils() {
	}

	/**
	 * Creates the NanoVG context and loads the fonts, once.
	 *
	 * <p>Deliberately not a static initialiser. {@code nvgCreate} needs a current GL context and the
	 * fonts need a resource manager that has finished its first reload - neither is guaranteed while
	 * the {@code Minecraft} constructor (where client entrypoints run) is still on the stack. Calling
	 * this from {@link #beginFrame} means it runs on the render thread with a live context by
	 * construction.
	 */
	private static void ensureInit() {
		if (initialised) {
			return;
		}
		initialised = true;
		try {
			vg = nvgCreate(NVG_ANTIALIAS | NVG_STENCIL_STROKES);
			// nvgCreate returns NULL on failure. rsm checks for -1, which never happens.
			if (vg == 0L) {
				throw new IllegalStateException("nvgCreate returned NULL");
			}

			nvgPaint = NVGPaint.malloc();
			nvgColor = NVGColor.malloc();
			nvgColor2 = NVGColor.malloc();
			fontBounds = new float[] {0f, 0f, 0f, 0f};

			registerFont(JOSEFIN, Identifier.fromNamespaceAndPath("austrianpainter", "font/josefin.ttf"));
			registerFont(JOSEFIN_BOLD, Identifier.fromNamespaceAndPath("austrianpainter", "font/josefin-bold.ttf"));

			// Logged on success too, not just on failure: this runs lazily on the first frame that
			// draws, so without a line here a log with no error in it is ambiguous between "NanoVG
			// started fine" and "the paint UI was never opened".
			LOGGER.info("NanoVG started ({} fonts, gui scale {}, device pixel ratio {})",
					registeredFonts.size(), guiScale(), devicePixelRatio());
		} catch (Throwable t) {
			// Throwable, not Exception: a missing native surfaces as UnsatisfiedLinkError.
			disabled = true;
			LOGGER.error("NanoVG failed to start; the paint UI will fall back to vanilla drawing", t);
		}
	}

	/** True once NanoVG has failed to start; callers draw their vanilla fallback instead. */
	public static boolean isDisabled() {
		return disabled;
	}

	/**
	 * Starts NanoVG if it has not started, and reports whether it can be used.
	 *
	 * <p>The measuring calls below are the reason this is public. Widgets lay out in {@code init},
	 * which runs before the first frame draws, so {@code vg} would still be 0 - and passing 0 to
	 * LWJGL is not an exception, it is a segfault that takes the game with it. Anything measuring
	 * outside a draw must gate on this.
	 *
	 * <p>Only safe on the render thread: it may create the GL-backed context.
	 */
	public static boolean isReady() {
		ensureInit();
		return !disabled && vg != 0L;
	}

	public static long getVg() {
		return vg;
	}

	public static boolean isDrawing() {
		return drawing;
	}

	public static void registerFont(String name, Identifier path) {
		try {
			var resource = Minecraft.getInstance().getResourceManager().getResource(path)
					.orElseThrow(() -> new IllegalStateException("Missing bundled font " + path));
			registeredFonts.put(name, new NvgFont(name, resource.open()));
		} catch (Exception e) {
			throw new RuntimeException("Failed to register font " + name, e);
		}
	}

	public static NvgFont getFont(String name) {
		// The fonts are registered by startup, so a caller that arrives before the first frame would
		// otherwise be told the font does not exist - which is true, but only for another microsecond.
		ensureInit();
		NvgFont font = registeredFonts.get(name);
		if (font == null) {
			throw new IllegalArgumentException("Font \"" + name + "\" is not registered!");
		}
		return font;
	}

	/**
	 * Framebuffer pixels per screen pixel. On a HiDPI display this is above 1, and NanoVG needs it to
	 * pick the right glyph raster.
	 */
	public static float devicePixelRatio() {
		try {
			Window window = Minecraft.getInstance().getWindow();
			int framebufferWidth = window.getWidth();
			int screenWidth = window.getScreenWidth();
			return screenWidth == 0 ? 1f : (float) framebufferWidth / screenWidth;
		} catch (Exception e) {
			return 1f;
		}
	}

	/**
	 * The factor that turns NanoVG's space into Minecraft GUI pixels.
	 *
	 * <p>Replaces rsm's {@code RSMConfig.getStandardGuiScale()}, which is <em>not</em> Minecraft's GUI
	 * scale at all - it is {@code clamp(max(h/1080, w/1920) / dpr, 1, 3)}, a 1920x1080 design-space
	 * factor. Copying it would put every pixel-exact layout in this mod slightly wrong.
	 */
	public static float guiScale() {
		try {
			return (float) (Minecraft.getInstance().getWindow().getGuiScale() / devicePixelRatio());
		} catch (Exception e) {
			return 1f;
		}
	}

	// ------------------------------------------------------------------ frame

	public static void beginFrame(float width, float height) {
		ensureInit();
		if (disabled) {
			return;
		}
		if (drawing) {
			throw new IllegalStateException("[NVGUtils] beginFrame called when already drawing");
		}

		float dpr = devicePixelRatio();
		nvgBeginFrame(vg, width / dpr, height / dpr, dpr);
		nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_TOP);
		drawing = true;
	}

	public static void endFrame() {
		if (disabled) {
			return;
		}
		if (!drawing) {
			throw new IllegalStateException("[NVGUtils] endFrame called when not drawing");
		}
		nvgEndFrame(vg);
		drawing = false;
	}

	// ------------------------------------------------------------------ transforms

	public static void push() {
		nvgSave(vg);
	}

	public static void pop() {
		nvgRestore(vg);
	}

	public static void scale(float x, float y) {
		nvgScale(vg, x, y);
	}

	public static void scale(float factor) {
		nvgScale(vg, factor, factor);
	}

	public static void translate(float x, float y) {
		nvgTranslate(vg, x, y);
	}

	public static void rotate(float angle) {
		nvgRotate(vg, angle);
	}

	public static void globalAlpha(float alpha) {
		nvgGlobalAlpha(vg, alpha);
	}

	public static void pushScissor(float x, float y, float w, float h) {
		scissor = new Scissor(scissor, x, y, w + x, h + y);
		scissor.applyScissor();
	}

	public static void popScissor() {
		nvgResetScissor(vg);
		if (scissor != null) {
			scissor = scissor.previous;
			if (scissor != null) {
				scissor.applyScissor();
			}
		}
	}

	// ------------------------------------------------------------------ shapes

	public static void drawLine(float x, float y, float x1, float y1, float thickness, Colour colour) {
		nvgBeginPath(vg);
		nvgMoveTo(vg, x, y);
		nvgLineTo(vg, x1, y1);
		nvgStrokeWidth(vg, thickness);
		colour(colour);
		nvgStrokeColor(vg, nvgColor);
		nvgStroke(vg);
	}

	public static void drawRect(double x, double y, double w, double h, double r, Colour colour) {
		drawRect((float) x, (float) y, (float) w, (float) h, (float) r, colour);
	}

	public static void drawRect(float x, float y, float w, float h, float r, Colour colour) {
		nvgBeginPath(vg);
		// The half pixel is upstream's, and it is load-bearing: without it adjacent rects leave a
		// hairline seam at some GUI scales. Do not "clean up".
		nvgRoundedRect(vg, x, y, w, h + .5f, r);
		colour(colour);
		nvgFillColor(vg, nvgColor);
		nvgFill(vg);
	}

	public static void drawRect(float x, float y, float w, float h, Colour colour) {
		nvgBeginPath(vg);
		nvgRect(vg, x, y, w, h);
		colour(colour);
		nvgFillColor(vg, nvgColor);
		nvgFill(vg);
	}

	/** A rect rounded on only one end - the shape a panel's accent bar needs to follow its corners. */
	public static void drawHalfRoundedRect(float x, float y, float w, float h, float r, boolean top, Colour colour) {
		nvgBeginPath(vg);

		if (top) {
			nvgMoveTo(vg, x, y + h);
			nvgLineTo(vg, x + w, y + h);
			nvgLineTo(vg, x + w, y + r);
			nvgArcTo(vg, x + w, y, x + w - r, y, r);
			nvgLineTo(vg, x + r, y);
			nvgArcTo(vg, x, y, x, y + r, r);
			nvgLineTo(vg, x, y + h);
		} else {
			nvgMoveTo(vg, x, y);
			nvgLineTo(vg, x + w, y);
			nvgLineTo(vg, x + w, y + h - r);
			nvgArcTo(vg, x + w, y + h, x + w - r, y + h, r);
			nvgLineTo(vg, x + r, y + h);
			nvgArcTo(vg, x, y + h, x, y + h - r, r);
			nvgLineTo(vg, x, y);
		}

		nvgClosePath(vg);
		colour(colour);
		nvgFillColor(vg, nvgColor);
		nvgFill(vg);
	}

	public static void drawOutlineRect(float x, float y, float w, float h, float r, float thickness, Colour colour) {
		nvgBeginPath(vg);
		nvgRoundedRect(vg, x, y, w, h, r);
		nvgStrokeWidth(vg, thickness);
		nvgPathWinding(vg, NVG_HOLE);
		colour(colour);
		nvgStrokeColor(vg, nvgColor);
		nvgStroke(vg);
	}

	public static void drawOutlineRect(float x, float y, float w, float h, float thickness, Colour colour) {
		nvgBeginPath(vg);
		nvgRect(vg, x, y, w, h);
		nvgStrokeWidth(vg, thickness);
		nvgPathWinding(vg, NVG_HOLE);
		colour(colour);
		nvgStrokeColor(vg, nvgColor);
		nvgStroke(vg);
	}

	public static void drawCircle(float x, float y, float r, Colour colour) {
		nvgBeginPath(vg);
		nvgCircle(vg, x, y, r);
		colour(colour);
		nvgFillColor(vg, nvgColor);
		nvgFill(vg);
	}

	public static void drawGradientRect(float x, float y, float w, float h, float r, Colour from, Colour to, Gradient direction) {
		nvgBeginPath(vg);
		nvgRoundedRect(vg, x, y, w, h, r);
		drawGradient(from, to, x, y, w, h, direction);
		nvgFillPaint(vg, nvgPaint);
		nvgFill(vg);
	}

	private static void drawGradient(Colour from, Colour to, float x, float y, float w, float h, Gradient direction) {
		colour(from, to);
		switch (direction) {
			case LeftToRight -> nvgLinearGradient(vg, x, y, x + w, y, nvgColor, nvgColor2, nvgPaint);
			case TopToBottom -> nvgLinearGradient(vg, x, y, x, y + h, nvgColor, nvgColor2, nvgPaint);
		}
	}

	public static void drawDropShadow(float x, float y, float w, float h, float blur, float spread, float r, Colour colour) {
		colour(colour);
		nvgRGBA((byte) 0, (byte) 0, (byte) 0, (byte) 0, nvgColor2);
		drawDropShadowUsingColours(x, y, w, h, blur, spread, r);
	}

	public static void drawDropShadow(float x, float y, float w, float h, float blur, float spread, float r) {
		nvgRGBA((byte) 0, (byte) 0, (byte) 0, (byte) 125, nvgColor);
		nvgRGBA((byte) 0, (byte) 0, (byte) 0, (byte) 0, nvgColor2);
		drawDropShadowUsingColours(x, y, w, h, blur, spread, r);
	}

	private static void drawDropShadowUsingColours(float x, float y, float w, float h, float blur, float spread, float r) {
		// The inner rect is punched out with a scissor rather than left alone, because the shadow's
		// alpha would otherwise stack over the panel and read as an outline.
		nvgSave(vg);

		nvgBoxGradient(vg, x - spread, y - spread, w + 2 * spread, h + 2 * spread, r + spread, blur, nvgColor, nvgColor2, nvgPaint);

		nvgBeginPath(vg);
		nvgRoundedRect(vg, x - spread - blur, y - spread - blur, w + 2 * (spread + blur), h + 2 * (spread + blur), r + spread);
		nvgFillPaint(vg, nvgPaint);
		nvgFill(vg);

		nvgScissor(vg, x, y, w, h);

		nvgBeginPath(vg);
		nvgRoundedRect(vg, x, y, w, h, r);
		nvgRGBA((byte) 0, (byte) 0, (byte) 0, (byte) 0, nvgColor);
		nvgFillColor(vg, nvgColor);
		nvgFill(vg);

		nvgResetScissor(vg);
		nvgRestore(vg);
	}

	/** A chevron; {@code expanded} points it down instead of up. */
	public static void drawArrow(float x, float y, float size, float width, Colour colour, boolean expanded) {
		nvgBeginPath(vg);

		if (expanded) {
			nvgMoveTo(vg, x + 0.2f * size, y + 0.3f * size);
			nvgLineTo(vg, x + 0.5f * size, y + 0.7f * size);
			nvgMoveTo(vg, x + 0.5f * size, y + 0.7f * size);
			nvgLineTo(vg, x + 0.8f * size, y + 0.3f * size);
		} else {
			nvgMoveTo(vg, x + 0.2f * size, y + 0.7f * size);
			nvgLineTo(vg, x + 0.5f * size, y + 0.3f * size);
			nvgMoveTo(vg, x + 0.5f * size, y + 0.3f * size);
			nvgLineTo(vg, x + 0.8f * size, y + 0.7f * size);
		}

		nvgStrokeWidth(vg, width);
		colour(colour);
		nvgStrokeColor(vg, nvgColor);
		nvgStroke(vg);
	}

	public static void drawCheckmark(float x, float y, float size, float thickness, Colour colour) {
		nvgBeginPath(vg);
		nvgMoveTo(vg, x + 0.1f * size, y + 0.7f * size);
		nvgLineTo(vg, x + 0.3f * size, y + 0.9f * size);
		nvgLineTo(vg, x + 0.8f * size, y + 0.4f * size);

		nvgStrokeWidth(vg, thickness);
		colour(colour);
		nvgStrokeColor(vg, nvgColor);
		nvgStroke(vg);
	}

	// ------------------------------------------------------------------ text

	public static void drawText(String text, float x, float y, float size, Colour colour, NvgFont font) {
		drawText(text, x, y, size, colour, font, 0f);
	}

	/**
	 * As above, with tracking.
	 *
	 * <p>The shell's uppercase labels are letterspaced - uppercase set solid reads as a block rather
	 * than as words, which is why every design that uses small caps for labels opens them up. NanoVG
	 * carries the spacing in its state, so it is reset afterwards or it would leak into the next
	 * string drawn in this frame.
	 */
	public static void drawText(String text, float x, float y, float size, Colour colour, NvgFont font, float letterSpacing) {
		nvgFontSize(vg, size);
		nvgFontFaceId(vg, getFontID(font));
		nvgTextLetterSpacing(vg, letterSpacing);
		colour(colour);
		nvgBeginPath(vg);
		nvgFillColor(vg, nvgColor);
		nvgText(vg, x, y, text);
		nvgTextLetterSpacing(vg, 0f);
	}

	public static void drawCenteredText(String text, float x, float y, float size, Colour colour, NvgFont font) {
		drawText(text, x - getTextWidth(text, size, font) / 2f, y, size, colour, font);
	}

	public static void drawTextShadow(String text, float x, float y, float size, Colour colour, NvgFont font) {
		nvgFontFaceId(vg, getFontID(font));
		nvgFontSize(vg, size);
		colour(TEXT_SHADOW);
		nvgFillColor(vg, nvgColor);
		nvgText(vg, round(x + 1f), round(y + 1f), text);

		colour(colour);
		nvgFillColor(vg, nvgColor);
		nvgText(vg, round(x), round(y), text);
	}

	public static void drawText(String text, float x, float y, float size, Colour colour, boolean shadow, NvgFont font) {
		if (shadow) {
			drawTextShadow(text, x, y, size, colour, font);
		} else {
			drawText(text, x, y, size, colour, font);
		}
	}

	public static void drawWrappedString(String text, float x, float y, float w, float size, Colour colour, NvgFont font, float lineHeight) {
		nvgFontSize(vg, size);
		nvgFontFaceId(vg, getFontID(font));
		nvgTextLineHeight(vg, lineHeight);
		colour(colour);
		nvgFillColor(vg, nvgColor);
		nvgTextBox(vg, x, y, w, text);
	}

	/**
	 * Advance width of {@code text}, in the same units the draw calls take.
	 *
	 * <p>Note this is NanoVG's measurement, not {@code Font.width} - the two do not agree, so any
	 * layout that positions a NanoVG-drawn string must measure it with this and never mix the two.
	 */
	public static float getTextWidth(String text, float size, NvgFont font) {
		return getTextWidth(text, size, font, 0f);
	}

	/** Measured with the same tracking it will be drawn at, or the two disagree by a pixel a letter. */
	public static float getTextWidth(String text, float size, NvgFont font, float letterSpacing) {
		if (!isReady()) {
			return 0f;
		}
		nvgFontSize(vg, size);
		nvgFontFaceId(vg, getFontID(font));
		nvgTextLetterSpacing(vg, letterSpacing);
		float width = nvgTextBounds(vg, 0f, 0f, text, fontBounds);
		nvgTextLetterSpacing(vg, 0f);
		return width;
	}

	public static float getTextHeight(String content, float size, NvgFont font) {
		if (!isReady()) {
			return 0f;
		}
		nvgFontSize(vg, size);
		nvgFontFaceId(vg, getFontID(font));
		nvgTextBounds(vg, 0f, 0f, content, fontBounds);
		return fontBounds[3] - fontBounds[1];
	}

	public static float getTextHeight(float size, NvgFont font) {
		return getTextHeight("G", size, font);
	}

	public static int getFontID(NvgFont font) {
		NVGFontHandle handle = fontMap.get(font);
		if (handle != null) {
			return handle.id();
		}
		ByteBuffer buffer = font.buffer();
		int fontId = nvgCreateFontMem(vg, font.getName(), buffer, true);
		fontMap.put(font, new NVGFontHandle(fontId, buffer));
		return fontId;
	}

	// ------------------------------------------------------------------ colour

	public static void colour(Colour colour) {
		nvgRGBA(colour.getRedByte(), colour.getGreenByte(), colour.getBlueByte(), colour.getAlphaByte(), nvgColor);
	}

	public static void colour(Colour first, Colour second) {
		nvgRGBA(first.getRedByte(), first.getGreenByte(), first.getBlueByte(), first.getAlphaByte(), nvgColor);
		nvgRGBA(second.getRedByte(), second.getGreenByte(), second.getBlueByte(), second.getAlphaByte(), nvgColor2);
	}

	/** A clip rect that intersects with whatever is already pushed, so nesting narrows rather than replaces. */
	private static final class Scissor {

		private final Scissor previous;
		private final float x;
		private final float y;
		private final float maxX;
		private final float maxY;

		private Scissor(Scissor previous, float x, float y, float maxX, float maxY) {
			this.previous = previous;
			this.x = x;
			this.y = y;
			this.maxX = maxX;
			this.maxY = maxY;
		}

		private void applyScissor() {
			if (previous == null) {
				nvgScissor(vg, x, y, maxX - x, maxY - y);
			} else {
				float clippedX = Math.max(this.x, previous.x);
				float clippedY = Math.max(this.y, previous.y);
				float width = Math.max(0f, Math.min(maxX, previous.maxX) - clippedX);
				float height = Math.max(0f, Math.min(maxY, previous.maxY) - clippedY);
				nvgScissor(vg, clippedX, clippedY, width, height);
			}
		}
	}

	/** NanoVG owns the buffer once the font is created, so it is held here for the process lifetime. */
	private record NVGFontHandle(int id, ByteBuffer buffer) {
	}
}
