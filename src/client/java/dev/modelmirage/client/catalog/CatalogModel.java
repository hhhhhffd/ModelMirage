package dev.modelmirage.client.catalog;

import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;

/** The asset's purpose is independent of its file format and decoder. */
public record CatalogModel(String id, String name, Kind kind, String format, String group, int color,
		List<Identifier> items, Asset model, Map<Identifier, Asset> textures, Map<Identifier, Asset> parents) {
	public static final String BOYKISSER = "builtin-boykisser";
	public static CatalogModel boykisser() {
		return new CatalogModel(BOYKISSER, "BoyKisser", Kind.MASCOT, "sprite-animation", "Персонажи", 0xFF80D6ED, List.of(), null, Map.of(), Map.of());
	}
	public boolean builtin() { return BOYKISSER.equals(id); }
	public Identifier modelId() { return Identifier.fromNamespaceAndPath("modelmirage", "catalog/" + id); }
	public boolean accepts(Identifier item) { return items.isEmpty() || items.contains(item); }
	public enum Kind { ITEM, PLAYER, MASCOT }
	public record Asset(String path, String sha256, int bytes) { }
}
