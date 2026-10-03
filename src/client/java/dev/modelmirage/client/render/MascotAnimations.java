package dev.modelmirage.client.render;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import dev.modelmirage.client.catalog.CatalogModel;
import dev.modelmirage.client.catalog.ModelLoader;
import dev.modelmirage.client.visual.VisualPreferences;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/** Downloaded PNG sequences use the same movable HUD surface as the bundled BoyKisser. */
public final class MascotAnimations {
	private static final Map<Identifier, Animation> ANIMATIONS = new ConcurrentHashMap<>();
	private static final AtomicLong GENERATION = new AtomicLong();
	private MascotAnimations() { }

	public static ModelLoader.Prepared prepare(CatalogModel entry, byte[] definition, Map<Identifier, byte[]> textures, Map<Identifier, byte[]> parents) throws IOException {
		var object = JsonParser.parseString(new String(definition, StandardCharsets.UTF_8)).getAsJsonObject();
		int width = object.get("width").getAsInt();
		int height = object.get("height").getAsInt();
		int interval = object.get("frame_time_ms").getAsInt();
		var frames = object.getAsJsonArray("frames");
		if (width < 8 || height < 8 || width > 512 || height > 512 || interval < 16 || interval > 2000 || frames.isEmpty() || frames.size() > 256) throw new IOException("Invalid mascot animation dimensions/timing");
		List<Identifier> order = new ArrayList<>();
		Map<Identifier, NativeImage> images = new LinkedHashMap<>();
		try {
			long pixels = 0;
			for (var frame : frames) {
				Identifier id = Identifier.parse(frame.getAsString());
				order.add(id);
				if (images.containsKey(id)) continue;
				byte[] png = textures.get(id);
				if (png == null || png.length < 33 || ByteBuffer.wrap(png).getLong() != 0x89504E470D0A1A0AL || ByteBuffer.wrap(png, 12, 4).getInt() != 0x49484452) throw new IOException("Missing/invalid mascot PNG: " + id);
				int w = ByteBuffer.wrap(png, 16, 4).getInt();
				int h = ByteBuffer.wrap(png, 20, 4).getInt();
				pixels += (long) w * h;
				if (w < 1 || h < 1 || w > 2048 || h > 2048 || pixels > 16 * 1024 * 1024) throw new IOException("Mascot texture dimensions exceed limits");
				images.put(id, NativeImage.read(new ByteArrayInputStream(png)));
			}
		} catch (IOException | RuntimeException failure) { images.values().forEach(NativeImage::close); throw failure; }
		return new ModelLoader.Prepared() {
			public void install() {
				var manager = Minecraft.getInstance().getTextureManager();
				Map<Identifier, Identifier> installed = new LinkedHashMap<>();
				try {
					long generation = GENERATION.incrementAndGet();
					int index = 0;
					for (var image : images.entrySet()) {
						Identifier target = Identifier.fromNamespaceAndPath("modelmirage", "textures/runtime/mascot/" + entry.id() + "/" + generation + "/" + index++);
						DynamicTexture texture = new DynamicTexture(() -> "ModelMirage mascot " + entry.id(), image.getValue());
						try { manager.register(target, texture); }
						catch (RuntimeException failure) { texture.close(); throw failure; }
						installed.put(image.getKey(), target);
					}
					Animation previous = ANIMATIONS.put(entry.modelId(), new Animation(width, height, interval, order.stream().map(installed::get).toList(), List.copyOf(installed.values())));
					if (previous != null) previous.textures().forEach(manager::release);
				} catch (RuntimeException failure) {
					installed.values().forEach(manager::release);
					images.entrySet().stream().filter(image -> !installed.containsKey(image.getKey())).forEach(image -> image.getValue().close());
					throw failure;
				}
			}
			public void discard() { images.values().forEach(NativeImage::close); }
		};
	}

	public static int width(Identifier id) { Animation animation = ANIMATIONS.get(id); return animation == null ? 64 : animation.width(); }
	public static int height(Identifier id) { Animation animation = ANIMATIONS.get(id); return animation == null ? 64 : animation.height(); }
	public static void draw(GuiGraphicsExtractor graphics, Identifier id, int x, int y, int width, int height, int tint) {
		Animation animation = ANIMATIONS.get(id);
		Identifier texture;
		if (animation == null) {
			int frame = VisualPreferences.animations ? (int) (System.currentTimeMillis() / 100 % 52) : 0;
			texture = Identifier.fromNamespaceAndPath("modelmirage", "astolfo/boykisser/boykisser_" + frame + ".png");
		} else {
			int frame = VisualPreferences.animations ? (int) (System.currentTimeMillis() / animation.interval() % animation.frames().size()) : 0;
			texture = animation.frames().get(frame);
		}
		var image = Minecraft.getInstance().getTextureManager().getTexture(texture).getTexture();
		graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, width, height, image.getWidth(0), image.getHeight(0), image.getWidth(0), image.getHeight(0), tint);
	}
	public static void close() {
		var manager = Minecraft.getInstance().getTextureManager();
		ANIMATIONS.values().forEach(animation -> animation.textures().forEach(manager::release));
		ANIMATIONS.clear();
	}
	private record Animation(int width, int height, int interval, List<Identifier> frames, List<Identifier> textures) { }
}
