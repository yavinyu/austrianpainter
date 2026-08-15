package com.maxisch.client.gui

import com.maxisch.client.render.render2d.Colour
import com.maxisch.client.render.render2d.NVGUtils
import com.maxisch.client.render.render2d.NvgFont
import net.minecraft.client.Minecraft

/**
 * The paint UI's palette, corner radii and type scale, in one place.
 *
 * Every widget in `gui/widget` used to carry its own ARGB constants, so changing a shade meant
 * editing six files. Values stay ARGB ints because the vanilla draw calls that remain - item models,
 * and all text for now - still take ints; [c] adapts one for NanoVG.
 *
 * The palette is the warm near-black one from the shell mockup, built around the mod's own
 * [com.maxisch.paint.ApSettings.areaOutlineColor] amber. It replaces the cold greys this port
 * started with: those came from rsm, whose shell this UI no longer borrows.
 *
 * Semantic names below map onto the raw shades; widgets use the semantic ones, so a shade can move
 * without touching a widget.
 */
object Theme {

    // ---------------------------------------------------------------- raw palette

    /** Behind everything: the screen's own ground, and the well inside a card. */
    const val GROUND = 0xFF100F0D.toInt()

    /** Frame furniture and card bodies - one step up from [GROUND]. */
    const val SURFACE = 0xFF191714.toInt()

    /** A control sitting on a card: buttons, cells, chips. */
    const val RAISE = 0xFF201D19.toInt()

    const val RULE = 0xFF2E2A24.toInt()
    const val RULE_SOFT = 0xFF241F1A.toInt()

    const val INK = 0xFFEDE9E1.toInt()
    const val MUTED = 0xFF8C857A.toInt()
    const val DIM = 0xFF5E584F.toInt()

    /** Armed, active, selected. The one colour that means "this is live". */
    const val AMBER = 0xFFFFAA00.toInt()
    const val CYAN = 0xFF55FFFF.toInt()
    const val GREEN = 0xFF55FF55.toInt()
    const val RED = 0xFFFF6B6B.toInt()

    // ---------------------------------------------------------------- surfaces

    const val PANEL = SURFACE
    const val LIST_BACKGROUND = GROUND
    const val TRACK = GROUND

    // ---------------------------------------------------------------- lines

    const val OUTLINE = RULE
    const val OUTLINE_DIM = RULE_SOFT

    // ---------------------------------------------------------------- interaction

    /**
     * Hover and selection are amber at low alpha rather than white, so the eye reads "this one" in
     * the same colour the armed state uses everywhere else. Alphas are heavier than the mockup's
     * 9-13%: it draws on a flat page, and these sit over a moving world.
     */
    const val HOVER = 0x26FFAA00
    const val HOVER_TINT = 0x1FFFFFFF
    const val ZONE_HOVER = 0x26FFAA00
    const val SELECTED = 0x40FFAA00

    // ---------------------------------------------------------------- text

    const val TEXT = INK
    const val TEXT_DIM = MUTED

    /** For labels that must not compete with the value beside them. */
    const val TEXT_FAINT = DIM

    // ---------------------------------------------------------------- controls

    const val SWITCH_TRACK_OFF = 0xFF35302A.toInt()
    const val SWITCH_TRACK_DISABLED = RULE_SOFT
    const val KNOB = 0xFFE8E4DC.toInt()
    const val KNOB_DISABLED = DIM

    // ---------------------------------------------------------------- geometry

    /** Corner radius for panels, cards, tracks and list backgrounds. */
    const val RADIUS = 4f

    /** Chips, cells and anything else small enough that [RADIUS] would read as a lozenge. */
    const val RADIUS_SMALL = 3f

    const val OUTLINE_THICKNESS = 1f

    /**
     * NanoVG strokes centred on the path, so a 1px outline drawn on the exact widget rect straddles
     * the boundary by half a pixel and reads as fat or clipped. Insetting by half the thickness puts
     * it fully inside, which is what the old `graphics.outline` did.
     */
    const val OUTLINE_INSET = 0.5f

    // ---------------------------------------------------------------- fonts

    /**
     * The shipped faces.
     *
     * Only legal once NanoVG has started, which is why every use below sits inside a draw block or
     * behind [NVGUtils.isReady]: the fonts are registered by that startup, and asking for one before
     * it throws. A default argument is **not** behind the gate - Kotlin evaluates those at the call
     * site, before the function body runs - so these must never be a parameter default.
     */
    val body: NvgFont get() = NVGUtils.getFont(NVGUtils.JOSEFIN)
    val bold: NvgFont get() = NVGUtils.getFont(NVGUtils.JOSEFIN_BOLD)

    /**
     * Body size, chosen to sit on vanilla's 10px line box.
     *
     * Josefin's cap height is smaller than its em, so 11 reads at roughly vanilla's 9px size without
     * outgrowing the row heights every `doLayout` in this mod already assumes.
     */
    const val TEXT_SIZE = 11f

    /** Labels above a value, and anything else that must not compete with what it describes. */
    const val TEXT_SIZE_SMALL = 9f

    /**
     * Advance width of [text], in GUI pixels.
     *
     * **Every layout that positions NanoVG-drawn text must measure it with this.** NanoVG's metrics
     * and `Font.width` do not agree, and mixing them puts a label and its underline in different
     * places. Falls back to vanilla's metric when NanoVG is unavailable, which is correct precisely
     * because the widget will then be drawing with vanilla too.
     */
    fun textWidth(text: String, size: Float = TEXT_SIZE, font: NvgFont? = null): Float =
        if (NVGUtils.isReady()) {
            NVGUtils.getTextWidth(text, size, font ?: body)
        } else {
            Minecraft.getInstance().font.width(text).toFloat()
        }

    /** Cap height at [size], for centring text in a box. */
    fun textHeight(size: Float = TEXT_SIZE, font: NvgFont? = null): Float =
        if (NVGUtils.isReady()) {
            NVGUtils.getTextHeight(size, font ?: body)
        } else {
            Minecraft.getInstance().font.lineHeight.toFloat()
        }

    private val cache = HashMap<Int, Colour>()

    /**
     * The NanoVG colour for an ARGB int, interned.
     *
     * Every draw call runs once per widget per frame, so allocating here would be steady garbage for
     * no reason - the set of colours in play is small and fixed.
     */
    fun c(argb: Int): Colour = cache.getOrPut(argb) { Colour.of(argb) }

    /** The same colour at [alpha] (0-255), for the tints the frame builds out of an accent. */
    fun withAlpha(argb: Int, alpha: Int): Int = (argb and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
}
