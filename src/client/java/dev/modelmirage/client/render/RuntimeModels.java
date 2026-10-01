package dev.modelmirage.client.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.modelmirage.client.mixin.SpriteLoaderInvoker;
import dev.modelmirage.client.mixin.ItemStackRenderStateAccessor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.ModelRenderProperties;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvedModel;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.cuboid.CuboidModel;
import net.minecraft.client.resources.model.cuboid.ItemModelGenerator;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.MaterialBaker;
import net.minecraft.resources.Identifier;
import org.joml.Vector3fc;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.slf4j.LoggerFactory;

public final class RuntimeModels {
	private static final Map<Identifier, Loaded> MODELS = new ConcurrentHashMap<>();
	private static final AtomicLong GENERATION = new AtomicLong();

	private RuntimeModels() { }

	public static boolean contains(Identifier id) { return MODELS.containsKey(id); }
	public static ItemModel get(Identifier id) {
		Loaded loaded = MODELS.get(id);
		return loaded == null ? null : loaded.model();
	}

	public static Prepared prepare(Identifier id, byte[] model, Map<Identifier, byte[]> textures,
			Map<Identifier, byte[]> parents) throws IOException {
		ResolvedModel resolved = resolve(id, model, parents, new HashSet<>());
		List<SpriteContents> sprites = new ArrayList<>();
		try {
			long totalPixels = 0;
			for (var entry : textures.entrySet()) {
				byte[] png = entry.getValue();
				if (png.length < 33 || ByteBuffer.wrap(png).getLong() != 0x89504E470D0A1A0AL
						|| ByteBuffer.wrap(png, 12, 4).getInt() != 0x49484452) throw new IOException("Invalid PNG: " + entry.getKey());
				int width = ByteBuffer.wrap(png, 16, 4).getInt();
				int height = ByteBuffer.wrap(png, 20, 4).getInt();
				totalPixels += (long) width * height;
				if (width <= 0 || height <= 0 || width > 2048 || height > 2048 || totalPixels > 16 * 1024 * 1024) {
					throw new IOException("Texture dimensions exceed limits: " + entry.getKey());
				}
				NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
				sprites.add(new SpriteContents(entry.getKey(), new FrameSize(width, height), image));
			}
			sprites.add(MissingTextureAtlasSprite.create());
			return new Prepared(id, resolved, sprites);
		} catch (IOException | RuntimeException failure) {
			sprites.forEach(SpriteContents::close);
			throw failure;
		}
	}

	private static ResolvedModel resolve(Identifier id, byte[] json, Map<Identifier, byte[]> parents,
			HashSet<Identifier> chain) throws IOException {
		if (!chain.add(id) || chain.size() > 32) throw new IOException("Cyclic or excessive parent chain: " + id);
		JsonObject object = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
		if (object.has("elements") && object.getAsJsonArray("elements").size() > 4096) throw new IOException("Too many model elements");
		CuboidModel model = CuboidModel.fromStream(new StringReader(object.toString()));
		ResolvedModel parent = null;
		Identifier parentId = model.parent();
		if (parentId != null) {
			if (parentId.equals(ItemModelGenerator.GENERATED_ITEM_MODEL_ID)) {
				parent = new Resolved(parentId.toString(), new ItemModelGenerator(), null);
			} else {
				byte[] parentJson = parents.get(parentId);
				if (parentJson == null) {
					if (!"minecraft".equals(parentId.getNamespace())) throw new IOException("Missing model parent: " + parentId);
					var location = parentId.withPath(path -> "models/" + path + ".json");
					try (var input = Minecraft.getInstance().getResourceManager().getResourceOrThrow(location).open()) {
						parentJson = input.readNBytes(4 * 1024 * 1024 + 1);
						if (parentJson.length > 4 * 1024 * 1024) throw new IOException("Parent model too large");
					}
				}
				parent = resolve(parentId, parentJson, parents, chain);
			}
		}
		return new Resolved(id.toString(), model, parent);
	}

	public static void install(Prepared prepared) {
		Identifier atlasId = Identifier.fromNamespaceAndPath("modelmirage", "textures/runtime/" + prepared.id().getPath() + "/" + GENERATION.incrementAndGet());
		TextureAtlas atlas = new TextureAtlas(atlasId);
		boolean ownsSprites = false;
		try {
			SpriteLoader loader = SpriteLoader.create(atlas);
			var preparations = ((SpriteLoaderInvoker) loader).modelmirage$stitch(prepared.sprites(), 0, Runnable::run);
			atlas.upload(preparations);
			ownsSprites = true;
			MaterialBaker materials = new MaterialBaker(atlas.missingSprite()) {
				@Override
				protected Material.Baked bake(Material material) {
					var sprite = preparations.regions().get(material.sprite());
					if (sprite == null) throw new IllegalArgumentException("Missing dependent texture: " + material.sprite());
					return new Material.Baked(sprite, material.forceTranslucent());
				}
			};
			ModelBaker baker = new ModelBaker() {
				private final Map<SharedOperationKey<?>, Object> shared = new HashMap<>();
				private final Interner interner = new Interner() {
					@Override
					public Vector3fc vector(Vector3fc vector) { return vector; }
					@Override
					public BakedQuad.MaterialInfo materialInfo(BakedQuad.MaterialInfo material) {
						var renderType = material.layer().translucent() ? RenderTypes.itemTranslucent(atlasId) : RenderTypes.itemCutout(atlasId);
						return new BakedQuad.MaterialInfo(material.sprite(), material.layer(), renderType,
								material.tintIndex(), material.shade(), material.lightEmission());
					}
				};
				@Override
				public ResolvedModel getModel(Identifier id) {
					if (!prepared.id().equals(id)) throw new IllegalArgumentException("Unknown runtime model: " + id);
					return prepared.resolved();
				}
				@Override
				public BlockStateModelPart missingBlockModelPart() { throw new UnsupportedOperationException("Runtime item models only"); }
				@Override
				public MaterialBaker materials() { return materials; }
				@Override
				public Interner interner() { return interner; }
				@Override
				@SuppressWarnings("unchecked")
				public <T> T compute(SharedOperationKey<T> key) {
					T result = (T) shared.get(key);
					if (result == null) { result = key.compute(this); shared.put(key, result); }
					return result;
				}
			};
			var resolved = prepared.resolved();
			var slots = resolved.getTopTextureSlots();
			var collection = resolved.bakeTopGeometry(slots, baker, BlockModelRotation.IDENTITY);
			List<BakedQuad> quads = collection.getAll();
			if (quads.isEmpty()) throw new IllegalArgumentException("The model contains no renderable faces");
			ModelRenderProperties properties = ModelRenderProperties.fromResolvedModel(baker, resolved, slots);
			Vector3fc[] extents = CuboidItemModelWrapper.computeExtents(quads);
			Object modelIdentity = new Object();
			ItemModel model = (output, item, resolver, context, level, owner, seed) -> {
				output.appendModelIdentityElement(modelIdentity);
				var layer = output.newLayer();
				layer.setExtents(() -> extents);
				properties.applyToLayer(layer, context);
				layer.prepareQuadList().addAll(quads);
				if (item.hasFoil()) {
					layer.setFoilType(ItemStackRenderState.FoilType.STANDARD);
					output.appendModelIdentityElement(ItemStackRenderState.FoilType.STANDARD);
					output.setAnimated();
				}
			};
			Minecraft.getInstance().getTextureManager().register(atlasId, atlas);
			Loaded previous = MODELS.put(prepared.id(), new Loaded(model, atlasId, quads, properties, extents));
			if (previous != null) Minecraft.getInstance().getTextureManager().release(previous.atlasId());
			LoggerFactory.getLogger("ModelMirage").info("Runtime model ready: {} ({} faces, no resource reload)", prepared.id(), quads.size());
		} catch (RuntimeException failure) {
			atlas.close();
			if (!ownsSprites) prepared.sprites().forEach(SpriteContents::close);
			throw failure;
		}
	}

	public static void close() {
		for (Loaded loaded : MODELS.values()) Minecraft.getInstance().getTextureManager().release(loaded.atlasId());
		MODELS.clear();
	}

	public static void preview(GuiGraphicsExtractor graphics, Identifier id, float centerX, float centerY,
			float size, float yaw, float pitch) {
		Loaded loaded = MODELS.get(id);
		if (loaded == null) return;
		TrackingItemStackRenderState state = new TrackingItemStackRenderState();
		((ItemStackRenderStateAccessor) state).modelmirage$setDisplayContext(ItemDisplayContext.GUI);
		state.appendModelIdentityElement(loaded.model());
		state.appendModelIdentityElement(yaw);
		state.appendModelIdentityElement(pitch);
		state.appendModelIdentityElement(size);
		state.setOversizedInGui(true);
		var layer = state.newLayer();
		loaded.properties().applyToLayer(layer, ItemDisplayContext.GUI);
		PoseStack.Pose display = new PoseStack.Pose();
		loaded.properties().transforms().getTransform(ItemDisplayContext.GUI).apply(false, display);
		layer.setItemTransform(ItemTransform.NO_TRANSFORM);
		layer.setExtents(() -> loaded.extents());
		// Enlarge geometry before rendering so the native oversized-item renderer
		// allocates a full-resolution preview instead of magnifying a 16px icon.
		layer.setLocalTransform(new Matrix4f().translate(0.5f, 0.5f, 0.5f).scale(size / 16f).mul(display.pose())
				.translate(0.5f, 0.5f, 0.5f).rotateY(yaw).rotateX(pitch).translate(-0.5f, -0.5f, -0.5f));
		layer.prepareQuadList().addAll(loaded.quads());
		Matrix3x2f pose = new Matrix3x2f(graphics.pose()).translate(centerX - 8, centerY - 8);
		graphics.guiRenderState.addItem(new GuiItemRenderState(pose, state, 0, 0, graphics.scissorStack.peek()));
	}

	private record Loaded(ItemModel model, Identifier atlasId, List<BakedQuad> quads, ModelRenderProperties properties, Vector3fc[] extents) { }
	private record Resolved(String debugName, UnbakedModel wrapped, ResolvedModel parent) implements ResolvedModel { }
	public record Prepared(Identifier id, ResolvedModel resolved, List<SpriteContents> sprites) { }
}
