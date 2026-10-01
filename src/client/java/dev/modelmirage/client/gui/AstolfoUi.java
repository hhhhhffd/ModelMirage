package dev.modelmirage.client.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;

/** Layout palette, animation and font assets adapted from AstolfoVisuals ClickGuiScreen/GuiUtils. */
public final class AstolfoUi {
	public static final int BACKGROUND = 0xFF0E0E12;
	public static final int PANEL = 0xFF101016;
	public static final int CARD = 0xFF14141A;
	public static final int HOVER = 0xFF181820;
	public static final int TEXT = 0xFFE3E3ED;
	public static final int MUTED = 0xFF828291;
	public static final int ACCENT = 0xFFFF69B4;
	public static final int SELECTED = 0xFF382333;
	public static final Face SEMIBOLD = new Face("semibold");
	public static final Face MEDIUM = new Face("medium");
	public static final Face ICONS = new Face("icons");
	private static final RenderPipeline MSDF = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("modelmirage", "pipeline/msdf"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("modelmirage", "core/msdf")).build());

	private AstolfoUi() { }

	// GuiUtils.animate: frame-rate-independent easing from the upstream GUI.
	public static float animate(float current, float target, float speed, float seconds) {
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

	public static void rounded(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius, int color) {
		radius = Math.min(radius, Math.min(width, height) / 2);
		graphics.fill(x + radius, y, x + width - radius, y + height, color);
		graphics.fill(x, y + radius, x + width, y + height - radius, color);
		for (int row = 0; row < radius; row++) {
			int inset = radius - (int) Math.sqrt(radius * radius - Math.pow(radius - row - 0.5, 2));
			graphics.fill(x + inset, y + row, x + width - inset, y + row + 1, color);
			graphics.fill(x + inset, y + height - row - 1, x + width - inset, y + height - row, color);
		}
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
