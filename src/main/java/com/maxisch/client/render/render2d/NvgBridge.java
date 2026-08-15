/*
 * Derived from rs-mod/rsm, src/main/java/com/ricedotwho/rsm/render/render2d/NVGSpecialRenderer.java
 * Copyright (c) 2026, rice.who - https://github.com/rs-mod/rsm
 *
 * Modified for Austrian Painter:
 *   - the clip rectangle is a parameter instead of being read off GuiGraphicsExtractor.scissorStack,
 *     whose type is package-private and so cannot be named from this package
 *   - the framebuffer is created once per renderer instance instead of per draw call
 *   - the framebuffer is cleared before drawing; vanilla only clears when it reallocates the texture
 *   - a scale and origin translate are applied so callers work in Minecraft GUI pixels
 *   - GL state that NanoVG changes behind GlStateManager's back is saved and restored
 *   - the "no level" early return is gone, so the UI also draws outside a world
 *   - registration is an explicit call rather than rsm's KSP-discovered @Init
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
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

import com.maxisch.mixin.client.GuiGraphicsExtractorAccessor;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2f;
import org.lwjgl.opengl.GL33C;

/**
 * Runs NanoVG drawing inside Minecraft's GUI pass.
 *
 * <p>NanoVG cannot draw straight into the GUI the way {@code graphics.fill} does, so the work is
 * submitted as a picture-in-picture element: Minecraft renders it into its own texture and then
 * blits that texture back at the point in the layer stack where {@link #draw} was called. The useful
 * consequence is that anything drawn <em>after</em> a {@code draw} call - item models especially -
 * lands in a layer above it, because {@code GuiRenderState} tests picture-in-picture bounds for
 * intersection when it picks a layer. That is what lets the row lists mix NanoVG chrome with vanilla
 * block icons and still stack correctly.
 */
public final class NvgBridge extends PictureInPictureRenderer<NvgBridge.NvgRenderState> {

	/** Reused for the renderer's lifetime; rsm creates and destroys one per draw call. */
	private int framebuffer = 0;

	public NvgBridge(MultiBufferSource.BufferSource bufferSource) {
		super(bufferSource);
	}

	/** Called once from the client initialiser; the NanoVG context itself starts lazily on first frame. */
	public static void register() {
		PictureInPictureRendererRegistry.register(context ->
				new NvgBridge(context.minecraft().renderBuffers().bufferSource()));
	}

	/**
	 * Runs {@code content} inside a NanoVG frame whose coordinates are Minecraft GUI pixels, with the
	 * origin at the screen's top-left - so a widget passes its own {@code x}/{@code y} straight
	 * through to the draw calls.
	 *
	 * @param clip the clip rectangle in force, or {@code null} for none. Passed explicitly because
	 *             {@code GuiGraphicsExtractor.scissorStack} has a package-private type; every caller
	 *             here knows its own clip, since this mod's widgets are the only thing that pushes one.
	 */
	public static void draw(
			GuiGraphicsExtractor context,
			int x,
			int y,
			int width,
			int height,
			@Nullable ScreenRectangle clip,
			Runnable content
	) {
		if (NVGUtils.isDisabled()) {
			return;
		}

		Matrix3x2f pose = new Matrix3x2f(context.pose());
		ScreenRectangle bounds = createBounds(x, y, x + width, y + height, pose, clip);

		NvgRenderState state = new NvgRenderState(x, y, width, height, pose, clip, bounds, content);
		((GuiGraphicsExtractorAccessor) context).austrianpainter$guiRenderState().addPicturesInPictureState(state);
	}

	private static ScreenRectangle createBounds(int x0, int y0, int x1, int y1, Matrix3x2f pose, @Nullable ScreenRectangle clip) {
		ScreenRectangle rect = new ScreenRectangle(x0, y0, x1 - x0, y1 - y0).transformMaxBounds(pose);
		return clip != null ? clip.intersection(rect) : rect;
	}

	@Override
	protected void renderToTexture(NvgRenderState state, PoseStack poseStack) {
		var colourTexture = RenderSystem.outputColorTextureOverride;
		if (colourTexture == null) {
			return;
		}

		int width = colourTexture.getWidth(0);
		int height = colourTexture.getHeight(0);
		if (width == 0 || height == 0) {
			return;
		}

		if (!(colourTexture.texture() instanceof GlTexture glTexture)) {
			return;
		}

		if (framebuffer == 0) {
			framebuffer = GL33C.glGenFramebuffers();
		}

		// Restore whatever was bound rather than assuming zero: Minecraft rebinds per pass so zero
		// happens to survive, but that stops being true under Iris and Sodium.
		int previousFramebuffer = GL33C.glGetInteger(GL33C.GL_DRAW_FRAMEBUFFER_BINDING);
		int previousProgram = GL33C.glGetInteger(GL33C.GL_CURRENT_PROGRAM);
		int previousVertexArray = GL33C.glGetInteger(GL33C.GL_VERTEX_ARRAY_BINDING);
		int previousArrayBuffer = GL33C.glGetInteger(GL33C.GL_ARRAY_BUFFER_BINDING);
		int previousActiveTexture = GL33C.glGetInteger(GL33C.GL_ACTIVE_TEXTURE);
		boolean previousBlend = GL33C.glIsEnabled(GL33C.GL_BLEND);
		boolean previousDepthTest = GL33C.glIsEnabled(GL33C.GL_DEPTH_TEST);
		boolean previousCullFace = GL33C.glIsEnabled(GL33C.GL_CULL_FACE);
		boolean previousScissorTest = GL33C.glIsEnabled(GL33C.GL_SCISSOR_TEST);

		GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, framebuffer);
		GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0, GL33C.GL_TEXTURE_2D, glTexture.glId(), 0);
		GlStateManager._viewport(0, 0, width, height);

		// Vanilla only clears this texture on the frame it allocates it - textureIsReadyToBlit is
		// false by default, so every later frame renders into whatever was left behind. rsm never
		// notices because it draws one full-screen opaque layer; a per-widget layer would ghost.
		GL33C.glClearColor(0f, 0f, 0f, 0f);
		GL33C.glClear(GL33C.GL_COLOR_BUFFER_BIT);

		// NanoVG uses no sampler objects, so a sampler Minecraft left bound would corrupt its reads.
		GL33C.glBindSampler(0, 0);

		NVGUtils.beginFrame(width, height);
		NVGUtils.push();
		// The texture is sized in physical pixels and NanoVG was told about the device pixel ratio,
		// so this scale is what maps GUI pixels onto it; the translate then puts the origin at the
		// element's top-left, letting callers use absolute widget coordinates.
		NVGUtils.scale(NVGUtils.guiScale());
		NVGUtils.translate(-state.x(), -state.y());
		state.content().run();
		NVGUtils.pop();
		NVGUtils.endFrame();

		GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFramebuffer);
		GL33C.glUseProgram(previousProgram);
		GL33C.glBindVertexArray(previousVertexArray);
		GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, previousArrayBuffer);
		GL33C.glActiveTexture(previousActiveTexture);
		setEnabled(GL33C.GL_BLEND, previousBlend);
		setEnabled(GL33C.GL_DEPTH_TEST, previousDepthTest);
		setEnabled(GL33C.GL_CULL_FACE, previousCullFace);
		setEnabled(GL33C.GL_SCISSOR_TEST, previousScissorTest);
	}

	private static void setEnabled(int capability, boolean enabled) {
		if (enabled) {
			GL33C.glEnable(capability);
		} else {
			GL33C.glDisable(capability);
		}
	}

	@Override
	public void close() {
		if (framebuffer != 0) {
			GL33C.glDeleteFramebuffers(framebuffer);
			framebuffer = 0;
		}
		super.close();
	}

	@Override
	protected float getTranslateY(int height, int windowScaleFactor) {
		return height / 2f;
	}

	@Override
	public @NotNull Class<NvgRenderState> getRenderStateClass() {
		return NvgRenderState.class;
	}

	@Override
	protected @NotNull String getTextureLabel() {
		return "austrianpainter_nvg";
	}

	/**
	 * The component is named {@code pose} on purpose: naming it {@code poseMatrix}, as rsm does,
	 * leaves {@link PictureInPictureRenderState#pose()} resolving to the interface default identity,
	 * so a caller that had applied a pose transform would silently lose it.
	 */
	public record NvgRenderState(
			int x,
			int y,
			int width,
			int height,
			Matrix3x2f pose,
			@Nullable ScreenRectangle scissor,
			@Nullable ScreenRectangle bounds,
			Runnable content
	) implements PictureInPictureRenderState {

		@Override
		public float scale() {
			return 1f;
		}

		@Override
		public int x0() {
			return x;
		}

		@Override
		public int y0() {
			return y;
		}

		@Override
		public int x1() {
			return x + width;
		}

		@Override
		public int y1() {
			return y + height;
		}

		@Override
		public @Nullable ScreenRectangle scissorArea() {
			return scissor;
		}
	}
}
