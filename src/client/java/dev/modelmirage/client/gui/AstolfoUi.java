package dev.modelmirage.client.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;

/** Layout palette, animation and font assets adapted from AstolfoVisuals ClickGuiScreen/GuiUtils. */
public final class AstolfoUi {
	public static final int BACKGROUND = 0xFF000000;
	public static final int SIDEBAR = 0xFF000000;
	public static final int PANEL = 0xFF060608;
	public static final int CARD = 0xFF060608;
	public static final int HOVER = 0xFF0A0A0E;
	public static final int TEXT = 0xFFFFFFFF;
	public static final int MUTED = 0xFFA0A0A5;
	public static volatile int ACCENT = 0xFF80D6ED;
	public static final int SELECTED = 0xFF1C2F34;
	public static final int LINE = 0xFF16161C;
	public static float alpha = 1;
	public static final Face SEMIBOLD = new Face("semibold");
	public static final Face BOLD = new Face("bold");
	public static final Face MEDIUM = new Face("medium");
	public static final Face ICONS = new Face("icons");
	public static final Face LOGO = new Face("logo");
	public static final Face INTERFACE = new Face("interface");
	public static final Face WATERMARK = new Face("watermark");
	private static final RenderPipeline MSDF = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("modelmirage", "pipeline/msdf"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("modelmirage", "core/msdf")).build());
	private static final RenderPipeline SHAPE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("modelmirage", "pipeline/shape"))
			.withVertexShader(Identifier.fromNamespaceAndPath("modelmirage", "core/shape"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("modelmirage", "core/shape"))
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR).build());
	private static final RenderPipeline HEAD = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("modelmirage", "pipeline/head"))
			.withVertexShader(Identifier.fromNamespaceAndPath("modelmirage", "core/shape"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("modelmirage", "core/head")).build());
	private static final RenderPipeline GLASS = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("modelmirage", "pipeline/glass"))
			.withVertexShader(Identifier.fromNamespaceAndPath("modelmirage", "core/glass"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("modelmirage", "core/glass")).build());

	private AstolfoUi() { }

	// GuiUtils.animate: frame-rate-independent easing from the upstream GUI.
	public static float animate(float current, float target, float speed, float seconds) {
		if (!dev.modelmirage.client.visual.VisualPreferences.animations) return target;
		if (Math.abs(target - current) < 0.00005f) return target;
		return current + (target - current) * (1.0f - (float) Math.exp(-speed * seconds));
	}

	public static int mix(int from, int to, float factor) {
		int result = 0;
		for (int shift = 0; shift <= 24; shift += 8) {
			int start = (from >>> shift) & 255;
			int end = (to >>> shift) & 255;
			result |= (int) (start + (end - start) * Math.clamp(factor, 0f, 1f)) << shift;
		}
		return result;
	}

	/** Relative luminance 0..1, used to pick readable text on accent fills. */
	public static float luminance(int color) {
		return (0.2126f * (color >> 16 & 255) + 0.7152f * (color >> 8 & 255) + 0.0722f * (color & 255)) / 255f;
	}

	public static int opacity(int color, float amount) {
		return Math.round((color >>> 24) * Math.clamp(amount, 0, 1)) << 24 | color & 0xFFFFFF;
	}

	public static void shadow(GuiGraphicsExtractor graphics, float x, float y, float width, float height, float radius) {
		for (int i = 11; i >= 0; i--) {
			float progress = i / 12f;
			float spread = progress * 6.5f;
			shape(graphics, x - spread, y - spread + 0.8f, width + spread * 2, height + spread * 2,
					radius + spread, 0, opacity(0x66000000, (1 - progress) * (1 - progress)), opacity(0x66000000, (1 - progress) * (1 - progress)));
		}
	}

	public static void glow(GuiGraphicsExtractor graphics, float cx, float cy, float radius, int color, float strength) {
		for (int i = 7; i >= 0; i--) {
			float progress = i / 8f;
			float r = radius + progress * 6.5f;
			int tint = opacity(color, strength * (1 - progress) * (1 - progress));
			shape(graphics, cx - r, cy - r, r * 2, r * 2, r, 0, tint, tint);
		}
	}

	public static void rounded(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius, int color) {
		shape(graphics, x, y, width, height, radius, 0, color, color);
	}

	public static void border(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius, int color) {
		shape(graphics, x, y, width, height, radius, 1, color, color);
	}

	public static void shape(GuiGraphicsExtractor graphics, float x, float y, float width, float height,
			float radius, float border, int top, int bottom) {
		if (width <= 0 || height <= 0) return;
		Matrix3x2f pose = new Matrix3x2f(graphics.pose());
		ScreenRectangle bounds = new ScreenRectangle((int) x, (int) y, (int) Math.ceil(width), (int) Math.ceil(height)).transformMaxBounds(pose);
		ScreenRectangle scissor = graphics.scissorStack.peek();
		if (scissor != null) bounds = bounds.intersection(scissor);
		float meta = Math.min(Math.min(radius, Math.min(width, height) / 2), 999) + Math.round(border * 10) * 1000f;
		graphics.guiRenderState.addGuiElement(new ShapeRenderState(SHAPE, pose, x, y, width, height,
				meta, opacity(top, alpha), opacity(bottom, alpha), scissor, bounds, TextureSetup.noTexture()));
	}

	/**
	 * Liquid glass over the game frame: blurred, refracted at the rim and tinted by {@code tint}
	 * (its alpha is the tint strength). Blur is in GUI units, at most 63.
	 */
	public static void glass(GuiGraphicsExtractor graphics, float x, float y, float width, float height, float radius, float blur, int tint) {
		if (width <= 0 || height <= 0 || alpha <= 0.004f) return;
		var view = Backdrop.request();
		if (view == null) { shape(graphics, x, y, width, height, radius, 0, tint, tint); return; }
		Matrix3x2f pose = new Matrix3x2f(graphics.pose());
		ScreenRectangle bounds = new ScreenRectangle((int) x, (int) y, (int) Math.ceil(width), (int) Math.ceil(height)).transformMaxBounds(pose);
		ScreenRectangle scissor = graphics.scissorStack.peek();
		if (scissor != null) bounds = bounds.intersection(scissor);
		float clampedRadius = Math.min(Math.min(radius, Math.min(width, height) / 2), 127.5f);
		float meta = Math.round(clampedRadius * 2) + 256f * Math.clamp(Math.round(blur), 0, 63) + 16384f * Math.round(Math.clamp(alpha, 0, 1) * 63);
		TextureSetup setup = TextureSetup.singleTexture(view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
		graphics.guiRenderState.addGuiElement(new ShapeRenderState(GLASS, pose, x, y, width, height, meta, tint, tint, scissor, bounds, setup));
	}

	public static void head(GuiGraphicsExtractor graphics, Identifier textureId, int x, int y, int size, boolean player, int tint) {
		var texture = Minecraft.getInstance().getTextureManager().getTexture(textureId);
		TextureSetup textureSetup = TextureSetup.singleTexture(texture.getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
		Matrix3x2f pose = new Matrix3x2f(graphics.pose());
		ScreenRectangle bounds = new ScreenRectangle(x, y, size, size).transformMaxBounds(pose);
		ScreenRectangle scissor = graphics.scissorStack.peek();
		if (scissor != null) bounds = bounds.intersection(scissor);
		int color = opacity(tint, alpha);
		graphics.guiRenderState.addGuiElement(new ShapeRenderState(HEAD, pose, x, y, size, size, Math.min(4, size / 2f) + (player ? 0 : 10000f), color, color, scissor, bounds, textureSetup));
	}

	public static final class Face {
		private final String name;
		private final Map<Integer, Glyph> glyphs = new HashMap<>();
		private int atlasWidth;
		private int atlasHeight;
		private boolean loaded;
		private Face(String name) { this.name = name; }

		private void load() {
			if (loaded) return;
			Identifier data = Identifier.fromNamespaceAndPath("modelmirage", "astolfo/fonts/" + name + ".json");
			try (var reader = Minecraft.getInstance().getResourceManager().getResourceOrThrow(data).openAsReader()) {
				JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
				atlasWidth = root.getAsJsonObject("atlas").get("width").getAsInt();
				atlasHeight = root.getAsJsonObject("atlas").get("height").getAsInt();
				int index = 0;
				for (var entry : root.getAsJsonArray("glyphs")) {
					JsonObject glyph = entry.getAsJsonObject();
					int code = glyph.has("unicode") ? glyph.get("unicode").getAsInt() : 65 + index;
					index++;
					glyphs.put(code, new Glyph(glyph.get("advance").getAsFloat(), glyph.getAsJsonObject("planeBounds"), glyph.getAsJsonObject("atlasBounds")));
				}
				loaded = true;
			} catch (IOException | RuntimeException error) {
				throw new IllegalStateException("Could not load Astolfo GUI font " + name, error);
			}
		}

		public float width(String text, float size) {
			load();
			float width = 0;
			for (int code : text.codePoints().toArray()) {
				Glyph glyph = glyphs.getOrDefault(code, glyphs.get(63));
				width += glyph == null ? 0.5f * size : glyph.advance() * size;
			}
			return width;
		}

		public String fit(String text, float size, float maximum) {
			if (width(text, size) <= maximum) return text;
			while (!text.isEmpty() && width(text + "...", size) > maximum) text = text.substring(0, text.offsetByCodePoints(text.length(), -1));
			return text + "...";
		}

		public void draw(GuiGraphicsExtractor graphics, String text, float x, float y, float size, int color) {
			load();
			color = opacity(color, alpha);
			Identifier location = Identifier.fromNamespaceAndPath("modelmirage", "astolfo/fonts/" + name + ".png");
			var texture = Minecraft.getInstance().getTextureManager().getTexture(location);
			TextureSetup setup = TextureSetup.singleTexture(texture.getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			for (int code : text.codePoints().toArray()) {
				Glyph glyph = glyphs.getOrDefault(code, glyphs.get(63));
				if (glyph == null) { x += size * 0.5f; continue; }
				if (glyph.plane() != null && glyph.atlas() != null) {
					JsonObject plane = glyph.plane();
					JsonObject atlas = glyph.atlas();
					float left = plane.get("left").getAsFloat();
					float top = plane.get("top").getAsFloat();
					Matrix3x2f pose = new Matrix3x2f(graphics.pose()).translate(x + left * size, y + (0.8f - top) * size)
							.scale((plane.get("right").getAsFloat() - left) * size, (top - plane.get("bottom").getAsFloat()) * size);
					graphics.guiRenderState.addGuiElement(new BlitRenderState(MSDF, setup, pose, 0, 0, 1, 1,
							atlas.get("left").getAsFloat() / atlasWidth, atlas.get("right").getAsFloat() / atlasWidth,
							1f - atlas.get("top").getAsFloat() / atlasHeight, 1f - atlas.get("bottom").getAsFloat() / atlasHeight,
							color, graphics.scissorStack.peek()));
				}
				x += glyph.advance() * size;
			}
		}
		private record Glyph(float advance, JsonObject plane, JsonObject atlas) { }
	}
}
