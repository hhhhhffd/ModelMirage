package dev.modelmirage.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import dev.modelmirage.client.render.RuntimeModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ItemModelOverrides {
	private static final Logger LOGGER = LoggerFactory.getLogger("ModelMirage");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("modelmirage.json");
	private static volatile Map<Identifier, Identifier> overrides = Map.of();

	private ItemModelOverrides() {
	}

	public static void load() {
		if (!Files.exists(CONFIG)) return;
		try {
			overrides = parse(Files.readString(CONFIG, StandardCharsets.UTF_8), BuiltInRegistries.ITEM::containsKey);
		} catch (IOException | RuntimeException error) {
			LOGGER.error("Could not load {}", CONFIG, error);
		}
	}

	/** Validates every entry; one unsupported override rejects the whole file. */
	static Map<Identifier, Identifier> parse(String json, Predicate<Identifier> itemExists) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		JsonObject items = root.getAsJsonObject("items");
		if (items == null) throw new IllegalArgumentException("Missing items object");
		Map<Identifier, Identifier> loaded = new HashMap<>();
		items.entrySet().forEach(entry -> {
			Identifier item = Identifier.tryParse(entry.getKey());
			Identifier model = Identifier.tryParse(entry.getValue().getAsString());
			if (Identifier.fromNamespaceAndPath("modelmirage", "blood_axe_mace").equals(model)) model = ModelMirageClient.MODEL_ID;
			if (item == null || !itemExists.test(item) || model == null
					|| !model.getNamespace().equals("modelmirage") || !model.getPath().matches("catalog/[a-z0-9][a-z0-9_-]{0,63}")) {
				throw new IllegalArgumentException("Unsupported override: " + entry.getKey());
			}
			loaded.put(item, model);
		});
		return Map.copyOf(loaded);
	}

	public static boolean isEnabled(Identifier item) {
		return overrides.containsKey(item);
	}

	public static Identifier resolve(ItemStack stack, Identifier original) {
		if (original != null && original.getNamespace().equals("modelmirage") && original.getPath().startsWith("catalog/")) return original;
		Identifier item = BuiltInRegistries.ITEM.getKey(stack.getItem());
		Identifier selected = overrides.get(item);
		return selected != null && RuntimeModels.contains(selected) ? selected : original;
	}

	public static boolean usesModel(Identifier model) { return overrides.containsValue(model); }
	public static Identifier selectedModel(Identifier item) { return overrides.get(item); }
	public static int count() { return overrides.size(); }
	/** Immutable view of item → model assignments. */
	public static Map<Identifier, Identifier> all() { return overrides; }

	public static void setModel(Identifier item, Identifier model) throws IOException {
		Map<Identifier, Identifier> updated = new HashMap<>(overrides);
		if (model != null) updated.put(item, model);
		else updated.remove(item);

		write(CONFIG, updated);
		overrides = Map.copyOf(updated);
	}

	static void write(Path file, Map<Identifier, Identifier> assignments) throws IOException {
		JsonObject root = new JsonObject();
		JsonObject items = new JsonObject();
		assignments.forEach((itemId, modelId) -> items.addProperty(itemId.toString(), modelId.toString()));
		root.add("items", items);

		Files.createDirectories(file.getParent());
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
		try {
			Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException ignored) {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
