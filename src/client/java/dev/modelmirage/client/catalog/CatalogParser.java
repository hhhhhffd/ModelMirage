package dev.modelmirage.client.catalog;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;

/** Validates the untrusted catalog index; nothing here touches the network, disk or the game client. */
final class CatalogParser {
	static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
	private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_./-]+");

	private CatalogParser() { }

	static List<CatalogModel> parse(byte[] data) {
		if (data.length > 2 * 1024 * 1024) throw new IllegalArgumentException("Catalog too large");
		JsonObject root = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
		if (root.get("schema").getAsInt() != 1 && root.get("schema").getAsInt() != 2) throw new IllegalArgumentException("Unsupported catalog schema");
		var entries = root.getAsJsonArray("models");
		if (entries.size() > 4096) throw new IllegalArgumentException("Too many catalog models");
		List<CatalogModel> result = new ArrayList<>();
		result.add(CatalogModel.builtinMascot());
		HashSet<String> ids = new HashSet<>();
		ids.add(CatalogModel.BUILTIN_MASCOT);
		for (var value : entries) {
			JsonObject item = value.getAsJsonObject();
			String id = item.get("id").getAsString();
			String name = item.get("name").getAsString();
			String format = item.get("format").getAsString();
			CatalogModel.Kind kind = item.has("kind") ? CatalogModel.Kind.valueOf(item.get("kind").getAsString().toUpperCase(java.util.Locale.ROOT)) : CatalogModel.Kind.ITEM;
			String group = item.has("group") ? item.get("group").getAsString() : "";
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
}
