package dev.modelmirage.client.catalog;

import java.util.Map;
import net.minecraft.resources.Identifier;

public record CatalogModel(String id, String name, Asset model,
		Map<Identifier, Asset> textures, Map<Identifier, Asset> parents) {
	public Identifier modelId() {
		return Identifier.fromNamespaceAndPath("modelmirage", "catalog/" + id);
	}

	public record Asset(String path, String sha256, int bytes) {
	}
}
