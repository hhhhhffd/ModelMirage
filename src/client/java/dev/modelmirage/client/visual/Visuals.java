package dev.modelmirage.client.visual;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

public final class Visuals {
	private static final Map<String, VisualModule> MODULES = new LinkedHashMap<>();
	private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("modelmirage/visuals.json");
	private Visuals() { }

	public static void load() {
		try (var stream = Visuals.class.getResourceAsStream("/assets/modelmirage/visuals/modules.json")) {
			if (stream == null) throw new IOException("Missing visual module definitions");
			for (var definition : JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonArray()) {
				VisualModule module = new VisualModule(definition.getAsJsonObject());
				if (MODULES.put(module.id, module) != null) throw new IOException("Duplicate module " + module.id);
			}
		} catch (IOException | RuntimeException failure) {
			throw new IllegalStateException("Could not initialize visual modules", failure);
		}
		if (!Files.exists(CONFIG)) return;
		try {
			JsonObject config = JsonParser.parseString(Files.readString(CONFIG)).getAsJsonObject();
			for (VisualModule module : MODULES.values()) {
				JsonObject saved = config.getAsJsonObject(module.id);
				if (saved == null) continue;
				module.enabled = saved.get("enabled").getAsBoolean();
				module.bind = saved.has("bind") ? saved.get("bind").getAsInt() : -1;
				if (module.bind < -1 || module.bind > 348) throw new IllegalArgumentException("Invalid HUD key binding");
				JsonObject settings = saved.has("settings") ? saved.getAsJsonObject("settings") : new JsonObject();
				for (var entry : module.settings.entrySet()) {
					if (settings.has(entry.getKey())) entry.getValue().set(settings.get(entry.getKey()));
				}
			}
		} catch (IOException | RuntimeException failure) {
			LoggerFactory.getLogger("ModelMirage").error("Could not restore visual settings from {}", CONFIG, failure);
		}
	}

	public static List<VisualModule> modules() { return List.copyOf(MODULES.values()); }
	public static VisualModule module(String id) {
		VisualModule module = MODULES.get(id);
		if (module == null) throw new IllegalArgumentException("Unknown visual module " + id);
		return module;
	}
	public static boolean enabled(String id) { return module(id).enabled; }

	public static void save() throws IOException {
		JsonObject result = new JsonObject();
		for (VisualModule module : MODULES.values()) {
			JsonObject saved = new JsonObject();
			saved.addProperty("enabled", module.enabled);
			saved.addProperty("bind", module.bind);
			JsonObject settings = new JsonObject();
			module.settings.forEach((id, setting) -> settings.add(id, setting.value));
			saved.add("settings", settings);
			result.add(module.id, saved);
		}
		Files.createDirectories(CONFIG.getParent());
		Path temporary = Files.createTempFile(CONFIG.getParent(), "visuals-", ".tmp");
		try {
			Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(result));
			Files.move(temporary, CONFIG, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}
}
