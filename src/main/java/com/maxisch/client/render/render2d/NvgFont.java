/*
 * Derived from rs-mod/rsm, src/main/java/com/ricedotwho/rsm/render/render2d/Font.java
 * Copyright (c) 2026, rice.who - https://github.com/rs-mod/rsm
 *
 * Modified for Austrian Painter: Lombok's @Getter written out by hand, and renamed from Font to
 * NvgFont so it does not collide with net.minecraft.client.gui.Font, which the widgets import.
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

import org.lwjgl.BufferUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * A TTF loaded off the resource manager, held until NanoVG asks for it.
 *
 * <p>The buffer is built lazily and then kept: {@code nvgCreateFontMem} is called with
 * {@code freeData = true}, so NanoVG takes ownership and the buffer must outlive every draw. That is
 * also why nothing here frees it - the fonts live for the process.
 */
public final class NvgFont {

	private final String name;
	private final byte[] bytes;
	private ByteBuffer buffer = null;

	public NvgFont(String name, InputStream inputStream) {
		this.name = name;

		try (InputStream stream = inputStream) {
			bytes = stream.readAllBytes();
		} catch (IOException e) {
			throw new RuntimeException("Failed to read font " + name, e);
		}
	}

	public String getName() {
		return name;
	}

	public ByteBuffer buffer() {
		if (bytes == null) {
			throw new IllegalStateException("Font bytes not cached for font: " + this.name);
		}
		if (buffer == null) {
			buffer = BufferUtils.createByteBuffer(bytes.length);
			buffer.put(bytes);
			buffer.flip();
		}
		return buffer;
	}

	@Override
	public int hashCode() {
		return this.name.hashCode();
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof NvgFont font && font.getName().equals(this.name);
	}
}
