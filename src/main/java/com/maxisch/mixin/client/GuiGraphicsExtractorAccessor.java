package com.maxisch.mixin.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the private render state a {@link GuiGraphicsExtractor} collects into, so the NanoVG
 * bridge can submit a picture-in-picture element the same way vanilla's own map, entity and sign
 * renderers do.
 *
 * <p>{@link GuiRenderState} and its {@code addPicturesInPictureState} are both public - only the
 * field holding it is not, which is why an accessor is enough and no access widener is needed.
 * The sibling {@code scissorStack} field is deliberately left alone: its type is package-private,
 * so nothing outside {@code net.minecraft.client.gui} can name it. The bridge takes the clip
 * rectangle as a parameter instead, which the caller always knows - this mod's own widgets are the
 * only thing that pushes a scissor.
 */
@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsExtractorAccessor {

	@Accessor("guiRenderState")
	GuiRenderState austrianpainter$guiRenderState();
}
