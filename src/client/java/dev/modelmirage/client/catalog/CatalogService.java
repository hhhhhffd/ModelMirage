package dev.modelmirage.client.catalog;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CatalogService {
	public static final String DEFAULT_SOURCE = "https://raw.githubusercontent.com/hhhhhffd/ModelMirage-catolog/main/index.json";
	private static final Logger LOGGER = LoggerFactory.getLogger("ModelMirage");
	private static final Path CACHE = FabricLoader.getInstance().getConfigDir().resolve("modelmirage/cache");
	private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
	private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_./-]+");
	private static final ExecutorService WORKER = Executors.newFixedThreadPool(2,
			Thread.ofPlatform().daemon().name("ModelMirage-loader-", 0).factory());
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NEVER).build();
	private static final URI SOURCE = URI.create(System.getProperty("modelmirage.catalogUrl", DEFAULT_SOURCE));
	private static final Map<CatalogModel, CompletableFuture<Void>> DOWNLOADS = new ConcurrentHashMap<>();
	private static final Map<Identifier, CatalogModel> LOADED = new ConcurrentHashMap<>();
	private static final Map<String, Decoder> LOADERS = new ConcurrentHashMap<>();
	private static volatile List<CatalogModel> models = List.of(CatalogModel.boykisser());
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
		if (!ID.matcher(format).matches() || kinds.isEmpty()) throw new IllegalArgumentException("Invalid model loader " + format);
		if (LOADERS.putIfAbsent(format, new Decoder(Set.copyOf(kinds), Objects.requireNonNull(loader))) != null) throw new IllegalArgumentException("Duplicate model loader " + format);
	}
	public static boolean supports(CatalogModel model) {
		Decoder decoder = LOADERS.get(model.format());
		return model.builtin() || decoder != null && decoder.kinds().contains(model.kind());
	}
	private record Decoder(Set<CatalogModel.Kind> kinds, ModelLoader loader) { }

	public static List<CatalogModel> models() { return models; }
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
			Path indexCache = CACHE.resolve("catalog-" + digest(SOURCE.toString().getBytes(StandardCharsets.UTF_8)) + ".json");
			try {
				byte[] index = fetch(SOURCE, 2 * 1024 * 1024);
				List<CatalogModel> parsed = parse(index);
				writeCache(indexCache, index);
				models = parsed;
				cached = false;
			} catch (Exception onlineError) {
				try {
					models = parse(Files.readAllBytes(indexCache));
					cached = true;
					LOGGER.warn("Catalog unavailable; using verified local index: {}", onlineError.toString());
				} catch (Exception cacheError) {
					error = "Каталог недоступен. Проверь соединение и нажми \"Обновить\".";
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

	private static List<CatalogModel> parse(byte[] data) {
		if (data.length > 2 * 1024 * 1024) throw new IllegalArgumentException("Catalog too large");
		JsonObject root = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
		if (root.get("schema").getAsInt() != 1 && root.get("schema").getAsInt() != 2) throw new IllegalArgumentException("Unsupported catalog schema");
		var entries = root.getAsJsonArray("models");
		if (entries.size() > 4096) throw new IllegalArgumentException("Too many catalog models");
		List<CatalogModel> result = new ArrayList<>();
		result.add(CatalogModel.boykisser());
		HashSet<String> ids = new HashSet<>();
		ids.add(CatalogModel.BOYKISSER);
		for (var value : entries) {
			JsonObject item = value.getAsJsonObject();
			String id = item.get("id").getAsString();
			String name = item.get("name").getAsString();
			String format = item.get("format").getAsString();
			CatalogModel.Kind kind = item.has("kind") ? CatalogModel.Kind.valueOf(item.get("kind").getAsString().toUpperCase(java.util.Locale.ROOT)) : CatalogModel.Kind.ITEM;
			String group = item.has("group") ? item.get("group").getAsString() : "Модели";
			int color = item.has("color") ? 0xFF000000 | Integer.parseInt(item.get("color").getAsString().replace("#", ""), 16) & 0xFFFFFF : 0xFF6573FF;
			if (!ID.matcher(id).matches() || !ids.add(id) || name.isBlank() || name.length() > 128 || !ID.matcher(format).matches() || group.length() > 64) throw new IllegalArgumentException("Invalid catalog model: " + id);
			List<Identifier> targets = new ArrayList<>();
			if (item.has("items")) {
				if (item.getAsJsonArray("items").size() > 4096) throw new IllegalArgumentException("Too many target items");
				for (var target : item.getAsJsonArray("items")) targets.add(Identifier.parse(target.getAsString()));
			}
			var model = readAsset(item.getAsJsonObject("model"), 16 * 1024 * 1024);
			var textures = readAssets(item.getAsJsonObject("textures"), 16 * 1024 * 1024, kind == CatalogModel.Kind.MASCOT ? 256 : 32);
			var parents = readAssets(item.getAsJsonObject("parents"), 4 * 1024 * 1024, 32);
			long totalBytes = model.bytes() + textures.values().stream().mapToLong(CatalogModel.Asset::bytes).sum() + parents.values().stream().mapToLong(CatalogModel.Asset::bytes).sum();
			if (totalBytes > 32 * 1024 * 1024) throw new IllegalArgumentException("Invalid model asset size");
			result.add(new CatalogModel(id, name, kind, format, group, color, List.copyOf(targets), model, textures, parents));
		}
		return List.copyOf(result);
	}

	private static Map<Identifier, CatalogModel.Asset> readAssets(JsonObject object, int maximum, int count) {
		if (object == null) return Map.of();
		if (object.size() > count) throw new IllegalArgumentException("Too many dependencies");
		Map<Identifier, CatalogModel.Asset> result = new HashMap<>();
		for (var entry : object.entrySet()) result.put(Identifier.parse(entry.getKey()), readAsset(entry.getValue().getAsJsonObject(), maximum));
		return Map.copyOf(result);
	}

	private static CatalogModel.Asset readAsset(JsonObject object, int maximum) {
		String path = object.get("path").getAsString();
		String sha256 = object.get("sha256").getAsString();
		long bytes = object.get("bytes").getAsLong();
		if (!PATH.matcher(path).matches() || path.startsWith("/") || path.contains("//")
				|| List.of(path.split("/")).stream().anyMatch(part -> part.equals(".") || part.equals(".."))
				|| !sha256.matches("[a-f0-9]{64}") || bytes <= 0 || bytes > maximum) throw new IllegalArgumentException("Invalid catalog asset: " + path);
		return new CatalogModel.Asset(path, sha256, (int) bytes);
	}

	private static byte[] asset(CatalogModel.Asset asset) throws IOException, InterruptedException {
		Path cache = CACHE.resolve("objects/" + asset.sha256());
		if (Files.exists(cache) && Files.size(cache) == asset.bytes()) {
			byte[] data = Files.readAllBytes(cache);
			if (digest(data).equals(asset.sha256())) return data;
			LOGGER.warn("Discarding corrupt cache object {}", asset.sha256());
		}
		byte[] data = fetch(SOURCE.resolve(asset.path()), asset.bytes());
		if (data.length != asset.bytes() || !digest(data).equals(asset.sha256())) throw new IOException("Asset checksum mismatch: " + asset.path());
		writeCache(cache, data);
		return data;
	}

	private static byte[] fetch(URI url, int maximum) throws IOException, InterruptedException {
		if (!"https".equals(url.getScheme()) && !("http".equals(url.getScheme()) && List.of("localhost", "127.0.0.1", "[::1]").contains(url.getHost()))) {
			throw new IOException("Catalog requires HTTPS");
		}
		HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30)).header("User-Agent", "ModelMirage/0.2").GET().build();
		var response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream stream = response.body()) {
			if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + ": " + url);
			byte[] data = stream.readNBytes(maximum + 1);
			if (data.length > maximum) throw new IOException("Download exceeds size limit: " + url);
			return data;
		}
	}

	private static void writeCache(Path target, byte[] data) throws IOException {
		Files.createDirectories(target.getParent());
		Path temporary = Files.createTempFile(target.getParent(), "download-", ".tmp");
		try {
			Files.write(temporary, data);
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static String digest(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	public static void close() {
		WORKER.shutdownNow();
		HTTP.close();
	}
}
