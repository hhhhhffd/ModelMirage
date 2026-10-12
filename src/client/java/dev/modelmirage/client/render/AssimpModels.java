package dev.modelmirage.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Transparency;
import dev.modelmirage.client.catalog.CatalogModel;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.ModelState;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelDebugName;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.geometry.UnbakedGeometry;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Catalog loader for interchange 3D formats. Meshes are flattened into item quads on a worker thread
 * and installed through {@link RuntimeModels}, so they work as item and static player models.
 */
public final class AssimpModels {
	/** Catalog {@code format} → file extension Assimp picks its importer by. */
	public static final Map<String, String> FORMATS = Map.ofEntries(
			Map.entry("obj", "obj"), Map.entry("fbx", "fbx"), Map.entry("gltf", "gltf"), Map.entry("glb", "glb"),
			Map.entry("dae", "dae"), Map.entry("3ds", "3ds"), Map.entry("ply", "ply"), Map.entry("stl", "stl"),
			Map.entry("blend", "blend"), Map.entry("blender", "blend"));
	private static final int SOLID_SIZE = 16;
	// Handheld transforms in hand; a block-like angled view in GUI so the geometry reads as 3D.
	private static final String DEFINITION = """
			{"parent": "minecraft:item/handheld", "gui_light": "side", "textures": {"particle": "%s"},
			 "display": {"gui": {"rotation": [30, 225, 0], "scale": [0.625, 0.625, 0.625]},
			             "fixed": {"scale": [0.5, 0.5, 0.5]}}}""";

	private AssimpModels() { }

	public static RuntimeModels.Prepared prepare(CatalogModel entry, byte[] model, Map<Identifier, byte[]> textures,
			Map<Identifier, byte[]> files) throws IOException {
		Map<String, byte[]> sideFiles = new LinkedHashMap<>();
		files.forEach((id, data) -> sideFiles.put(id.getPath(), data));
		AssimpScene.Scene scene = AssimpScene.read(FORMATS.get(entry.format()), model, sideFiles);
		Identifier id = entry.modelId();
		Map<Identifier, SpriteContents> sprites = new LinkedHashMap<>();
		try {
			Identifier[] spriteOf = new Identifier[scene.materials().size()];
			boolean[] translucent = new boolean[spriteOf.length];
			long pixels = 0;
			// Files without material textures (e.g. OBJ without MTL) take a lone catalog texture for every material.
			List<String> embeddedNames = scene.embedded().stream().map(AssimpScene.Embedded::name).toList();
			boolean untextured = scene.materials().stream().allMatch(material -> material.texture() == null);
			for (int i = 0; i < spriteOf.length; i++) {
				AssimpScene.Material material = scene.materials().get(i);
				TextureSource source = resolveTexture(material.texture(), embeddedNames, textures.keySet(), untextured);
				if (source.embedded() >= 0) spriteOf[i] = id.withSuffix("/embedded_" + source.embedded());
				else if (source.catalog() != null) spriteOf[i] = source.catalog();
				else spriteOf[i] = id.withSuffix("/color_" + HexFormat.of().toHexDigits(material.color()));
				translucent[i] = material.color() >>> 24 < 255;
				if (sprites.containsKey(spriteOf[i])) continue;
				// Sizes come from headers, so the texture that would cross the budget is never decoded.
				if (source.embedded() >= 0) pixels += embeddedPixels(spriteOf[i], scene.embedded().get(source.embedded()));
				else if (source.catalog() != null) pixels += RuntimeModels.pngPixels(source.catalog(), textures.get(source.catalog()));
				else pixels += SOLID_SIZE * SOLID_SIZE;
				if (pixels > RuntimeModels.MAX_PIXELS) throw new IOException("Texture dimensions exceed limits: " + entry.id());
				if (source.embedded() >= 0) sprites.put(spriteOf[i], embeddedSprite(spriteOf[i], scene.embedded().get(source.embedded())));
				else if (source.catalog() != null) sprites.put(spriteOf[i], RuntimeModels.pngSprite(source.catalog(), textures.get(source.catalog())));
				else sprites.put(spriteOf[i], solid(spriteOf[i], material.color()));
			}
			List<SpriteContents> atlas = new ArrayList<>(sprites.values());
			atlas.add(MissingTextureAtlasSprite.create());
			var geometry = new MeshGeometry(normalized(scene.positions()), scene.uvs(), scene.materialOf(), spriteOf, translucent);
			var resolved = RuntimeModels.resolveWithGeometry(id, DEFINITION.formatted(spriteOf[scene.materialOf()[0]]), geometry);
			return new RuntimeModels.Prepared(id, resolved, atlas);
		} catch (IOException | RuntimeException failure) {
			sprites.values().forEach(SpriteContents::close);
			throw failure;
		}
	}

	/** Where a material's base color comes from; neither field set means a solid color sprite. */
	record TextureSource(int embedded, Identifier catalog) {
		static final TextureSource NONE = new TextureSource(-1, null);
	}

	/**
	 * Embedded textures first, then catalog textures by file name; a lone catalog texture is used for any reference,
	 * and for materials without one only when the whole file has no textures ({@code untextured}).
	 */
	static TextureSource resolveTexture(String reference, List<String> embeddedNames, Collection<Identifier> catalog,
			boolean untextured) throws IOException {
		Identifier lone = catalog.size() == 1 ? catalog.iterator().next() : null;
		if (reference == null) return untextured && lone != null ? new TextureSource(-1, lone) : TextureSource.NONE;
		int index = -1;
		if (reference.startsWith("*")) {
			try {
				index = Integer.parseInt(reference.substring(1));
			} catch (NumberFormatException invalid) {
				throw new IOException("Invalid embedded texture reference: " + reference);
			}
		} else {
			for (int i = 0; i < embeddedNames.size() && index < 0; i++) {
				if (AssimpScene.basename(embeddedNames.get(i)).equals(AssimpScene.basename(reference))) index = i;
			}
		}
		if (index >= 0 && index < embeddedNames.size()) return new TextureSource(index, null);
		String name = stem(reference);
		for (Identifier texture : catalog) {
			if (stem(texture.getPath()).equals(name)) return new TextureSource(-1, texture);
		}
		return lone != null ? new TextureSource(-1, lone) : TextureSource.NONE;
	}

	static String stem(String path) {
		String name = AssimpScene.basename(path);
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	private static SpriteContents solid(Identifier id, int argb) {
		NativeImage image = new NativeImage(SOLID_SIZE, SOLID_SIZE, false);
		image.fillRect(0, 0, SOLID_SIZE, SOLID_SIZE, argb);
		return new SpriteContents(id, new FrameSize(SOLID_SIZE, SOLID_SIZE), image);
	}

	/** Pixel count of an embedded texture, read from its header when it is compressed. */
	static long embeddedPixels(Identifier id, AssimpScene.Embedded texture) throws IOException {
		if (texture.argb() != null) return (long) texture.width() * texture.height();
		ByteBuffer encoded = MemoryUtil.memAlloc(texture.encoded().length).put(texture.encoded()).flip();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer width = stack.mallocInt(1), height = stack.mallocInt(1), channels = stack.mallocInt(1);
			if (!STBImage.stbi_info_from_memory(encoded, width, height, channels)) throw new IOException("Unsupported embedded texture: " + id);
			int w = width.get(0), h = height.get(0);
			if (w <= 0 || h <= 0 || w > RuntimeModels.MAX_TEXTURE_SIZE || h > RuntimeModels.MAX_TEXTURE_SIZE) {
				throw new IOException("Texture dimensions exceed limits: " + id);
			}
			return (long) w * h;
		} finally {
			MemoryUtil.memFree(encoded);
		}
	}

	private static SpriteContents embeddedSprite(Identifier id, AssimpScene.Embedded texture) throws IOException {
		if (texture.argb() != null) {
			NativeImage image = new NativeImage(texture.width(), texture.height(), false);
			for (int y = 0; y < texture.height(); y++) {
				for (int x = 0; x < texture.width(); x++) image.setPixel(x, y, texture.argb()[y * texture.width() + x]);
			}
			return new SpriteContents(id, new FrameSize(texture.width(), texture.height()), image);
		}
		ByteBuffer encoded = MemoryUtil.memAlloc(texture.encoded().length).put(texture.encoded()).flip();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer width = stack.mallocInt(1), height = stack.mallocInt(1), channels = stack.mallocInt(1);
			if (!STBImage.stbi_info_from_memory(encoded, width, height, channels)) throw new IOException("Unsupported embedded texture: " + id);
			int w = width.get(0), h = height.get(0);
			if (w <= 0 || h <= 0 || w > RuntimeModels.MAX_TEXTURE_SIZE || h > RuntimeModels.MAX_TEXTURE_SIZE) {
				throw new IOException("Texture dimensions exceed limits: " + id);
			}
			ByteBuffer rgba = STBImage.stbi_load_from_memory(encoded, width, height, channels, 4);
			if (rgba == null) throw new IOException("Could not decode embedded texture " + id + ": " + STBImage.stbi_failure_reason());
			try {
				NativeImage image = new NativeImage(w, h, false);
				for (int i = 0; i < w * h; i++) {
					int r = rgba.get(i * 4) & 0xFF, g = rgba.get(i * 4 + 1) & 0xFF, b = rgba.get(i * 4 + 2) & 0xFF, a = rgba.get(i * 4 + 3) & 0xFF;
					image.setPixel(i % w, i / w, a << 24 | r << 16 | g << 8 | b);
				}
				return new SpriteContents(id, new FrameSize(w, h), image);
			} finally {
				STBImage.stbi_image_free(rgba);
			}
		} finally {
			MemoryUtil.memFree(encoded);
		}
	}

	/** Fits the mesh into the unit block centred at (0.5, 0.5, 0.5); display transforms then size it like any item. */
	static float[] normalized(float[] positions) {
		Vector3f min = new Vector3f(Float.POSITIVE_INFINITY), max = new Vector3f(Float.NEGATIVE_INFINITY);
		for (int i = 0; i < positions.length; i += 3) {
			Vector3f point = new Vector3f(positions[i], positions[i + 1], positions[i + 2]);
			min.min(point);
			max.max(point);
		}
		Vector3f size = new Vector3f(max).sub(min);
		float scale = 1f / Math.max(1e-6f, Math.max(size.x, Math.max(size.y, size.z)));
		float[] result = new float[positions.length];
		for (int i = 0; i < positions.length; i += 3) {
			result[i] = (positions[i] - (min.x + max.x) / 2) * scale + 0.5f;
			result[i + 1] = (positions[i + 1] - (min.y + max.y) / 2) * scale + 0.5f;
			result[i + 2] = (positions[i + 2] - (min.z + max.z) / 2) * scale + 0.5f;
		}
		return result;
	}

	/** Triangles become quads whose last vertex repeats the third, which Minecraft's quad pipeline draws as one triangle. */
	private record MeshGeometry(float[] positions, float[] uvs, int[] materialOf, Identifier[] spriteOf,
			boolean[] translucent) implements UnbakedGeometry {
		@Override
		public QuadCollection bake(TextureSlots slots, ModelBaker baker, ModelState state, ModelDebugName name) {
			var interner = baker.interner();
			BakedQuad.MaterialInfo[] materials = new BakedQuad.MaterialInfo[spriteOf.length];
			QuadCollection.Builder builder = new QuadCollection.Builder();
			for (int t = 0; t < materialOf.length; t++) {
				int m = materialOf[t];
				if (materials[m] == null) {
					Material.Baked baked = baker.materials().get(new Material(spriteOf[m], translucent[m]), name);
					materials[m] = interner.materialInfo(BakedQuad.MaterialInfo.of(baked,
							translucent[m] ? Transparency.TRANSLUCENT : Transparency.TRANSPARENT, -1, true, 0));
				}
				Vector3f[] corners = new Vector3f[3];
				for (int c = 0; c < 3; c++) corners[c] = new Vector3f(positions[t * 9 + c * 3], positions[t * 9 + c * 3 + 1], positions[t * 9 + c * 3 + 2]);
				Vector3f normal = new Vector3f(corners[1]).sub(corners[0]).cross(new Vector3f(corners[2]).sub(corners[0]));
				if (normal.lengthSquared() < 1e-12f) continue;
				// Atlas sprites cannot repeat, so tiled UVs are shifted next to the origin and clamped into the sprite.
				float shiftU = (float) Math.floor(Math.min(uvs[t * 6], Math.min(uvs[t * 6 + 2], uvs[t * 6 + 4])));
				float shiftV = (float) Math.floor(Math.min(uvs[t * 6 + 1], Math.min(uvs[t * 6 + 3], uvs[t * 6 + 5])));
				long[] packed = new long[3];
				var sprite = materials[m].sprite();
				for (int c = 0; c < 3; c++) {
					float u = Math.clamp(uvs[t * 6 + c * 2] - shiftU, 0f, 1f);
					float v = Math.clamp(uvs[t * 6 + c * 2 + 1] - shiftV, 0f, 1f);
					packed[c] = UVPair.pack(sprite.getU(u), sprite.getV(v));
				}
				Vector3fc p0 = interner.vector(corners[0]), p1 = interner.vector(corners[1]), p2 = interner.vector(corners[2]);
				builder.addUnculledFace(new BakedQuad(p0, p1, p2, p2, packed[0], packed[1], packed[2], packed[2],
						Direction.getApproximateNearest(normal.x, normal.y, normal.z), materials[m]));
			}
			return builder.build();
		}
	}
}
