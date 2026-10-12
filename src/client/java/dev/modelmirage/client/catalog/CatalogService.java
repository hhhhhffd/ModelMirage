package dev.modelmirage.client.catalog;

import dev.modelmirage.client.ItemModelOverrides;
import dev.modelmirage.client.CosmeticSelections;
import dev.modelmirage.client.render.AssimpModels;
import dev.modelmirage.client.render.MascotAnimations;
import java.util.Set;
import java.util.Objects;
import dev.modelmirage.client.render.RuntimeModels;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
	private static final Duration DOWNLOAD_DEADLINE = Duration.ofSeconds(30);
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
	/** All files are on disk, so loading needs no network; previews may load such models on their own. */
	public static boolean isCached(CatalogModel model) {
		return model.builtin() || model.assets().stream().allMatch(asset -> CatalogFiles.present(CACHE.resolve("objects/" + asset.sha256()), asset));
	}
	public static boolean hasFailed(CatalogModel model) { var download = DOWNLOADS.get(model); return download != null && download.isCompletedExceptionally(); }

	public static synchronized CompletableFuture<Void> refresh() {
		if (loading) return CompletableFuture.completedFuture(null);
		loading = true;
		error = "";
		return CompletableFuture.runAsync(() -> {
			String name = "catalog-" + CatalogFiles.sha256(SOURCE.toString().getBytes(StandardCharsets.UTF_8));
			Path indexCache = CACHE.resolve(name + ".json"), tagCache = CACHE.resolve(name + ".etag");
			try {
				// An unchanged index answers 304 to its saved ETag, so a launch costs a few hundred bytes, not the index.
				String tag = Files.exists(indexCache) && Files.exists(tagCache) ? Files.readString(tagCache).strip() : null;
				Fetched fetched = fetch(SOURCE, 2 * 1024 * 1024, tag);
				List<CatalogModel> parsed = null;
				if (fetched.data() == null) {
					try { parsed = CatalogParser.parse(Files.readAllBytes(indexCache)); }
					catch (IOException | RuntimeException unreadable) {
						// A damaged local copy must not be confirmed by 304 forever.
						LOGGER.warn("Cached catalog index is unreadable, downloading it again: {}", unreadable.toString());
						fetched = fetch(SOURCE, 2 * 1024 * 1024, null);
					}
				}
				byte[] index = fetched.data();
				if (parsed == null) parsed = CatalogParser.parse(index);
				if (fetched.data() != null) {
					CatalogFiles.write(indexCache, index);
					if (fetched.tag() != null) CatalogFiles.write(tagCache, fetched.tag().getBytes(StandardCharsets.UTF_8));
					else Files.deleteIfExists(tagCache);
				}
				models = parsed;
				cached = false;
				pruneCache(parsed);
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

	/** Old versions and removed entries are dropped once the server has confirmed the current index. */
	private static void pruneCache(List<CatalogModel> current) {
		Set<String> keep = new HashSet<>();
		for (CatalogModel entry : current) for (CatalogModel.Asset asset : entry.assets()) keep.add(asset.sha256());
		try {
			long freed = CatalogFiles.prune(CACHE.resolve("objects"), keep);
			if (freed > 0) LOGGER.info("Removed {} bytes of cached files the catalog no longer uses", freed);
		} catch (IOException failure) {
			LOGGER.warn("Could not clean the model cache: {}", failure.toString());
		}
	}

	private static byte[] asset(CatalogModel.Asset asset) throws IOException, InterruptedException {
		Path cache = CACHE.resolve("objects/" + asset.sha256());
		byte[] cached = CatalogFiles.readVerified(cache, asset);
		if (cached != null) return cached;
		byte[] data = CatalogFiles.verified(asset, fetch(SOURCE.resolve(asset.path()), asset.bytes(), null).data());
		CatalogFiles.write(cache, data);
		return data;
	}

	/** {@code data} is null when the server confirmed {@code ifNoneMatch} with 304 Not Modified. */
	record Fetched(byte[] data, String tag) { }

	private static Fetched fetch(URI url, int maximum, String ifNoneMatch) throws IOException, InterruptedException {
		return fetch(HTTP, url, maximum, ifNoneMatch, DOWNLOAD_DEADLINE);
	}

	/**
	 * The request timeout only covers the response headers, so the whole exchange, body included, runs against
	 * {@code deadline} and is cancelled when a server stalls mid-body.
	 */
	static Fetched fetch(HttpClient http, URI url, int maximum, String ifNoneMatch, Duration deadline) throws IOException, InterruptedException {
		if (!"https".equals(url.getScheme()) && !("http".equals(url.getScheme()) && List.of("localhost", "127.0.0.1", "[::1]").contains(url.getHost()))) {
			throw new IOException("Catalog requires HTTPS");
		}
		HttpRequest.Builder request = HttpRequest.newBuilder(url).timeout(deadline).header("User-Agent", USER_AGENT).GET();
		if (ifNoneMatch != null) request.header("If-None-Match", ifNoneMatch);
		var exchange = http.sendAsync(request.build(), info -> info.statusCode() == 200
				? new LimitedBody(maximum, url) : HttpResponse.BodySubscribers.replacing((byte[]) null));
		HttpResponse<byte[]> response;
		try {
			response = exchange.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException timeout) {
			exchange.cancel(true);
			throw new IOException("Download timed out after " + deadline.toMillis() + " ms: " + url);
		} catch (InterruptedException interrupted) {
			exchange.cancel(true);
			throw interrupted;
		} catch (ExecutionException failure) {
			if (failure.getCause() instanceof IOException io) throw io;
			throw new IOException("Download failed: " + url, failure.getCause());
		}
		if (ifNoneMatch != null && response.statusCode() == 304) return new Fetched(null, ifNoneMatch);
		if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + ": " + url);
		return new Fetched(response.body(), response.headers().firstValue("ETag").orElse(null));
	}

	/** Collects the body and gives up as soon as it grows past {@code maximum}, without buffering the rest. */
	private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
		private final int maximum;
		private final URI url;
		private final ByteArrayOutputStream data = new ByteArrayOutputStream();
		private final CompletableFuture<byte[]> result = new CompletableFuture<>();
		private Flow.Subscription subscription;

		LimitedBody(int maximum, URI url) { this.maximum = maximum; this.url = url; }

		@Override public CompletionStage<byte[]> getBody() { return result; }
		@Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(Long.MAX_VALUE); }
		@Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
		@Override public void onComplete() { result.complete(data.toByteArray()); }

		@Override
		public void onNext(List<ByteBuffer> buffers) {
			if (result.isDone()) return;
			for (ByteBuffer buffer : buffers) {
				if (buffer.remaining() > maximum - data.size()) {
					subscription.cancel();
					result.completeExceptionally(new IOException("Download exceeds size limit: " + url));
					return;
				}
				byte[] chunk = new byte[buffer.remaining()];
				buffer.get(chunk);
				data.writeBytes(chunk);
			}
		}
	}

	public static void close() {
		WORKER.shutdownNow();
		HTTP.close();
	}
}
