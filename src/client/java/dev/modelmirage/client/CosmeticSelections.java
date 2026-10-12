package dev.modelmirage.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.modelmirage.client.catalog.CatalogModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.LoggerFactory;

/** Selections survive an empty/offline catalog; renderers keep the vanilla fallback until loading finishes. */
public final class CosmeticSelections {
	public enum Slot { SELF, OTHERS, MASCOT }
	private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("modelmirage/changer.json");
	private static volatile Map<Slot, Identifier> selections = Map.of();
	private CosmeticSelections() { }
	public static Identifier selected(Slot slot) {
		return selections.getOrDefault(slot, slot == Slot.MASCOT ? CatalogModel.boykisser().modelId() : null);
	}
	/** True only for an explicit choice; the HUD mascot otherwise falls back to the built-in one. */
	public static boolean isSet(Slot slot) { return selections.containsKey(slot); }
	public static boolean uses(Identifier id) { return selections.containsValue(id); }
	public static void load() {
		if (!Files.exists(CONFIG)) return;
		try {
			JsonObject object = JsonParser.parseString(Files.readString(CONFIG)).getAsJsonObject();
			Map<Slot, Identifier> loaded = new EnumMap<>(Slot.class);
			for (Slot slot : Slot.values()) {
				if (!object.has(slot.name().toLowerCase(java.util.Locale.ROOT))) continue;
				Identifier id = Identifier.parse(object.get(slot.name().toLowerCase(java.util.Locale.ROOT)).getAsString());
				validate(id);
				loaded.put(slot, id);
			}
			selections = Map.copyOf(loaded);
		} catch (IOException | RuntimeException failure) { LoggerFactory.getLogger("ModelMirage").error("Could not load changer selections", failure); }
	}
	public static void set(Slot slot, Identifier id) throws IOException {
		if (id != null) validate(id);
		Map<Slot, Identifier> updated = new EnumMap<>(Slot.class);
		updated.putAll(selections);
		if (id == null) updated.remove(slot); else updated.put(slot, id);
		JsonObject object = new JsonObject();
		updated.forEach((key, value) -> object.addProperty(key.name().toLowerCase(java.util.Locale.ROOT), value.toString()));
		Files.createDirectories(CONFIG.getParent());
		Path temporary = Files.createTempFile(CONFIG.getParent(), "changer-", ".tmp");
		try { Files.writeString(temporary, object.toString()); Files.move(temporary, CONFIG, StandardCopyOption.REPLACE_EXISTING); }
		finally { Files.deleteIfExists(temporary); }
		selections = Map.copyOf(updated);
	}
	private static void validate(Identifier id) {
		if (!id.getNamespace().equals("modelmirage") || !id.getPath().matches("catalog/[a-z0-9][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid cosmetic model " + id);
	}
}
