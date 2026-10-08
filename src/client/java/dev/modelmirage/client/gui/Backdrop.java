package dev.modelmirage.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;

/** Copy of the frame below the GUI, sampled by glass shapes; captured only on frames that draw glass. */
public final class Backdrop {
	private static GpuTexture texture;
	private static GpuTextureView view;
	private static boolean requested;

	private Backdrop() { }

	/** Called while extracting GUI state; the copy itself happens right before the GUI draws. */
	static GpuTextureView request() {
		GpuTexture source = Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture();
		if (source == null) return null;
		int width = source.getWidth(0), height = source.getHeight(0);
		if (texture == null || texture.isClosed() || texture.getWidth(0) != width || texture.getHeight(0) != height || texture.getFormat() != source.getFormat()) {
			close();
			var device = RenderSystem.getDevice();
			texture = device.createTexture("ModelMirage glass backdrop", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, source.getFormat(), width, height, 1, 1);
			view = device.createTextureView(texture);
		}
		requested = true;
		return view;
	}

	public static void capture() {
		if (!requested) return;
		requested = false;
		GpuTexture source = Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture();
		if (source == null || texture == null || source.getWidth(0) != texture.getWidth(0) || source.getHeight(0) != texture.getHeight(0)) return;
		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(source, texture, 0, 0, 0, 0, 0, texture.getWidth(0), texture.getHeight(0));
	}

	public static void close() {
		if (view != null) view.close();
		if (texture != null) texture.close();
		view = null;
		texture = null;
	}
}
