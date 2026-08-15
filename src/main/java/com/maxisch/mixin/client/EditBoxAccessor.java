package com.maxisch.mixin.client;

import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the caret, selection anchor and horizontal scroll out of {@link EditBox}.
 *
 * {@code com.maxisch.client.gui.widget.ApEditBox} draws the field's text itself, in the shipped
 * font, so that a text field does not sit among NanoVG-drawn labels showing vanilla's bitmap glyphs.
 * Every one of those three values is private with no getter, and all three are needed to draw text
 * that agrees with where vanilla thinks the caret is - the alternative is reimplementing the input
 * handling as well, which is the half worth keeping.
 */
@Mixin(EditBox.class)
public interface EditBoxAccessor {

	@Accessor("cursorPos")
	int austrianpainter$cursorPos();

	@Accessor("highlightPos")
	int austrianpainter$highlightPos();

	/** First visible character; vanilla scrolls this to keep the caret in view. */
	@Accessor("displayPos")
	int austrianpainter$displayPos();
}
