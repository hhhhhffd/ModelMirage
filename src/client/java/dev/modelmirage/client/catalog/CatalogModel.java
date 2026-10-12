package dev.modelmirage.client.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;

/** The asset's purpose is independent of its file format and decoder. An empty {@code group} means the default label for its kind. */
public record CatalogModel(String id, String name, Kind kind, String format, String group, int color,
		List<Identifier> items, Asset model, Map<Identifier, Asset> textures, Map<Identifier, Asset> parents) {
	public static final String BUILTIN_MASCOT = "builtin-mascot";
	public static CatalogModel builtinMascot() {
		return new CatalogModel(BUILTIN_MASCOT, "BoyKisser", Kind.MASCOT, "sprite-animation", "", 0xFF80D6ED, List.of(), null, Map.of(), Map.of());
	}
	public boolean builtin() { return BUILTIN_MASCOT.equals(id); }
	public Identifier modelId() { return Identifier.fromNamespaceAndPath("modelmirage", "catalog/" + id); }
	public boolean accepts(Identifier item) { return items.isEmpty() || items.contains(item); }
	/** Every file the entry needs; the built-in mascot has none. */
	public List<Asset> assets() {
		List<Asset> result = new ArrayList<>();
		if (model != null) result.add(model);
		result.addAll(textures.values());
		result.addAll(parents.values());
		return result;
	}
	/** Download size from the catalog, so the menu can show it before anything is fetched. */
	public long bytes() { return assets().stream().mapToLong(Asset::bytes).sum(); }
	public enum Kind { ITEM, PLAYER, MASCOT }
	public record Asset(String path, String sha256, int bytes) { }
}
