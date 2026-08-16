package com.maxisch.client.render.render2d;

/**
 * A packed ARGB colour, in the shape NanoVG wants it.
 *
 * <p>This is deliberately <em>not</em> a port of rsm's {@code type.Colour}, which stores HSBA in a
 * {@code short[]} and rebuilds RGB on every read. Round-tripping an ARGB int through that is lossy:
 * {@code 0xFF3A3A3A} becomes brightness {@code 58/255 = 22.7%}, truncates to {@code (short) 22}, and
 * comes back as {@code 0x38}. Every hand-picked grey in this UI would drift a shade. Holding the int
 * verbatim keeps the port faithful to the colours the widgets already use.
 *
 * <p>Instances are immutable and interned by {@code Theme.c}, because the draw path runs every frame
 * for every widget and this would otherwise be pure garbage.
 */
public final class Colour {

	private final int argb;

	private Colour(int argb) {
		this.argb = argb;
	}

	public static Colour of(int argb) {
		return new Colour(argb);
	}

	public static Colour of(int red, int green, int blue, int alpha) {
		return new Colour((alpha & 0xFF) << 24 | (red & 0xFF) << 16 | (green & 0xFF) << 8 | (blue & 0xFF));
	}

	/** The packed value, for the vanilla draw calls that still take an int. */
	public int argb() {
		return argb;
	}

	/** The same colour at a different opacity; {@code factor} is clamped to 0..1. */
	public Colour alpha(float factor) {
		float clamped = Math.max(0f, Math.min(1f, factor));
		int scaled = Math.round(((argb >>> 24) & 0xFF) * clamped);
		return new Colour(scaled << 24 | (argb & 0x00FFFFFF));
	}

	// NanoVG's nvgRGBA takes bytes, and Java bytes are signed - the casts below are the usual
	// truncation, not a conversion, so 0xFF arrives as -1 and NanoVG reads it back as 255.

	public byte getRedByte() {
		return (byte) ((argb >> 16) & 0xFF);
	}

	public byte getGreenByte() {
		return (byte) ((argb >> 8) & 0xFF);
	}

	public byte getBlueByte() {
		return (byte) (argb & 0xFF);
	}

	public byte getAlphaByte() {
		return (byte) ((argb >>> 24) & 0xFF);
	}

	@Override
	public int hashCode() {
		return argb;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Colour colour && colour.argb == argb;
	}

	@Override
	public String toString() {
		return String.format("Colour(#%08X)", argb);
	}
}
