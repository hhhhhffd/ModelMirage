package dev.modelmirage.client.catalog;

import dev.modelmirage.client.ItemModelOverrides;
import dev.modelmirage.client.CosmeticSelections;
import dev.modelmirage.client.render.AssimpModels;
import dev.modelmirage.client.render.MascotAnimations;
import java.util.Set;
import java.util.Objects;
import dev.modelmirage.client.render.RuntimeModels;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CatalogService {
	public static final String DEFAULT_SOURCE = "https://raw.githubusercontent.com/hhhhhffd/ModelMirage-catolog/main/index.json";
	private static final Logger LOGGER = LoggerFactory.getLogger("ModelMirage");
	private static final Path CACHE = FabricLoader.getInstance().getConfigDir().resolve("modelmirage/cache");
	private static final ExecutorService WORKER = Executors.newFixedThreadPool(2,
			Thread.ofPlatform().daemon().name("ModelMirage-loader-", 0).factory());
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NEVER).build();
	private static final String USER_AGENT = "ModelMirage/" + FabricLoader.getInstance().getModContainer("modelmirage")
			.map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("dev");
	private static final URI SOURCE = URI.create(System.getProperty("modelmirage.catalogUrl", DEFAULT_SOURCE));
	private static final Map<CatalogModel, CompletableFuture<Void>> DOWNLOADS = new ConcurrentHashMap<>();
	private static final Map<Identifier, CatalogModel> LOADED = new ConcurrentHashMap<>();
	private static final Map<String, Decoder> LOADERS = new ConcurrentHashMap<>();
	private static volatile List<CatalogModel> models = List.of(CatalogModel.builtinMascot());
	/** Translation key of the catalog error shown to the player. */
	public static final String ERROR_UNAVAILABLE = "modelmirage.catalog.error.unavailable";
	private static volatile String error = "";
	private static volatile boolean loading;
	private static volatile boolean cached;

	static {
		registerLoader("java-item", Set.of(CatalogModel.Kind.ITEM, CatalogModel.Kind.PLAYER),
				(entry, definition, textures, parents) -> RuntimeModels.prepare(entry.modelId(), definition, textures, parents));
		registerLoader("sprite-animation", Set.of(CatalogModel.Kind.MASCOT), MascotAnimations::prepare);
		for (String format : AssimpModels.FORMATS.keySet()) {
			registerLoader(format, Set.of(CatalogModel.Kind.ITEM, CatalogModel.Kind.PLAYER), AssimpModels::prepare);
		}
	}

	private CatalogService() { }

	public static void registerLoader(String format, Set<CatalogModel.Kind> kinds, ModelLoader loader) {
		if (!CatalogParser.ID.matcher(format).matches() || kinds.isEmpty()) throw new IllegalArgumentException("Invalid model loader " + format);
		if (LOADERS.putIfAbsent(format, new Decoder(Set.copyOf(kinds), Objects.requireNonNull(loader))) != null) throw new IllegalArgumentException("Duplicate model loader " + format);
	}
	public static boolean supports(CatalogModel model) {
		Decoder decoder = LOADERS.get(model.format());
		return model.builtin() || decoder != null && decoder.kinds().contains(model.kind());
	}
	private record Decoder(Set<CatalogModel.Kind> kinds, ModelLoader loader) { }

	public static List<CatalogModel> models() { return models; }
	/** Empty, or a translation key such as {@link #ERROR_UNAVAILABLE}. */
	public static String error() { return error; }
	public static boolean loading() { return loading; }
	public static boolean cached() { return cached; }
	public static boolean isLoaded(CatalogModel model) { return model.builtin() || model.equals(LOADED.get(model.modelId())); }
	public static boolean isLoading(CatalogModel model) { var download = DOWNLOADS.get(model); return download != null && !download.isDone(); }
	public static boolean hasFailed(CatalogModel model) { var download = DOWNLOADS.get(model); return download != null && download.isCompletedExceptionally(); }

	public static synchronized CompletableFuture<Void> refresh() {
		if (loading) return CompletableFuture.completedFuture(null);
		loading = true;
		error = "";
		return CompletableFuture.runAsync(() -> {
			Path indexCache = CACHE.resolve("catalog-" + CatalogFiles.sha256(SOURCE.toString().getBytes(StandardCharsets.UTF_8)) + ".json");
			try {
				byte[] index = fetch(SOURCE, 2 * 1024 * 1024);
				List<CatalogModel> parsed = CatalogParser.parse(index);
				CatalogFiles.write(indexCache, index);
				models = parsed;
				cached = false;
			} catch (Exception onlineError) {
				try {
					models = CatalogParser.parse(Files.readAllBytes(indexCache));
					cached = true;
					LOGGER.warn("Catalog unavailable; using verified local index: {}", onlineError.toString());
				} catch (Exception cacheError) {
					error = ERROR_UNAVAILABLE;
					LOGGER.error("Could not load catalog {}", SOURCE, onlineError);
					LOGGER.warn("No usable catalog cache: {}", cacheError.toString());
				}
			} finally {
				loading = false;
			}
			for (CatalogModel entry : models) {
				if ((ItemModelOverrides.usesModel(entry.modelId()) || CosmeticSelections.uses(entry.modelId())) && supports(entry)) load(entry);
			}
		}, WORKER);
	}

	public static CompletableFuture<Void> load(CatalogModel model) {
		if (isLoaded(model)) return CompletableFuture.completedFuture(null);
		if (!supports(model)) return CompletableFuture.failedFuture(new IllegalArgumentException("No loader for " + model.kind() + "/" + model.format()));
		return DOWNLOADS.computeIfAbsent(model, requested -> CompletableFuture.supplyAsync(() -> {
			try {
				byte[] json = asset(model.model());
				Map<Identifier, byte[]> textures = new HashMap<>();
				for (var entry : model.textures().entrySet()) textures.put(entry.getKey(), asset(entry.getValue()));
				Map<Identifier, byte[]> parents = new HashMap<>();
				for (var entry : model.parents().entrySet()) parents.put(entry.getKey(), asset(entry.getValue()));
				return LOADERS.get(model.format()).loader().prepare(model, json, textures, parents);
			} catch (Exception failure) {
				throw new java.util.concurrent.CompletionException(failure);
			}
		}, WORKER).thenCompose(prepared -> Minecraft.getInstance().submit(() -> {
				if (!models.contains(model)) {
					prepared.discard();
					throw new IllegalStateException("Catalog entry changed during download");
				}
				prepared.install();
				LOADED.put(model.modelId(), model);
			}))
				.whenComplete((unused, failure) -> {
					if (failure != null) LOGGER.error("Could not load model {}", model.id(), failure);
				}));
	}

	public static void retry(CatalogModel model) {
		DOWNLOADS.remove(model);
	}

	private static byte[] asset(CatalogModel.Asset asset) throws IOException, InterruptedException {
		Path cache = CACHE.resolve("objects/" + asset.sha256());
		byte[] cached = CatalogFiles.readVerified(cache, asset);
		if (cached != null) return cached;
		byte[] data = CatalogFiles.verified(asset, fetch(SOURCE.resolve(asset.path()), asset.bytes()));
		CatalogFiles.write(cache, data);
		return data;
	}

	private static byte[] fetch(URI url, int maximum) throws IOException, InterruptedException {
		if (!"https".equals(url.getScheme()) && !("http".equals(url.getScheme()) && List.of("localhost", "127.0.0.1", "[::1]").contains(url.getHost()))) {
			throw new IOException("Catalog requires HTTPS");
		}
		HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30)).header("User-Agent", USER_AGENT).GET().build();
		var response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream stream = response.body()) {
			if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + ": " + url);
			byte[] data = stream.readNBytes(maximum + 1);
			if (data.length > maximum) throw new IOException("Download exceeds size limit: " + url);
			return data;
		}
	}

	public static void close() {
		WORKER.shutdownNow();
		HTTP.close();
	}
}
