package dev.modelmirage.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
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
			JsonObject root = JsonParser.parseString(Files.readString(CONFIG, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonObject items = root.getAsJsonObject("items");
			if (items == null) throw new IllegalArgumentException("Missing items object");
			Map<Identifier, Identifier> loaded = new HashMap<>();
			items.entrySet().forEach(entry -> {
				Identifier item = Identifier.tryParse(entry.getKey());
				Identifier model = Identifier.tryParse(entry.getValue().getAsString());
				if (item == null || !BuiltInRegistries.ITEM.containsKey(item) || !ModelMirageClient.MODEL_ID.equals(model)) {
					throw new IllegalArgumentException("Unsupported override: " + entry.getKey());
				}
				loaded.put(item, model);
			});
			overrides = Map.copyOf(loaded);
		} catch (IOException | RuntimeException error) {
			LOGGER.error("Could not load {}", CONFIG, error);
		}
	}

	public static boolean isEnabled(Identifier item) {
		return overrides.containsKey(item);
	}

	public static Identifier resolve(ItemStack stack, Identifier original) {
		Identifier item = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return overrides.getOrDefault(item, original);
	}

	public static void setEnabled(Identifier item, boolean enabled) throws IOException {
		Map<Identifier, Identifier> updated = new HashMap<>(overrides);
		if (enabled) updated.put(item, ModelMirageClient.MODEL_ID);
		else updated.remove(item);

		JsonObject root = new JsonObject();
		JsonObject items = new JsonObject();
		updated.forEach((itemId, modelId) -> items.addProperty(itemId.toString(), modelId.toString()));
		root.add("items", items);

		Files.createDirectories(CONFIG.getParent());
		Path temp = CONFIG.resolveSibling("modelmirage.json.tmp");
		Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
		try {
			Files.move(temp, CONFIG, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException ignored) {
			Files.move(temp, CONFIG, StandardCopyOption.REPLACE_EXISTING);
		}
		overrides = Map.copyOf(updated);
	}
}
