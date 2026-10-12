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
			migratePanels(config);
			migrateMascot(config);
			migrateInterface(config);
			migrateArmor(config);
			for (VisualModule module : MODULES.values()) {
				JsonObject saved = config.getAsJsonObject(module.id);
				if (saved == null) continue;
				module.enabled = saved.get("enabled").getAsBoolean();
				module.bind = saved.has("bind") ? saved.get("bind").getAsInt() : -1;
				if (module.bind < -1 || module.bind > 348) throw new IllegalArgumentException("Invalid HUD key binding");
				JsonObject settings = saved.has("settings") ? saved.getAsJsonObject("settings") : new JsonObject();
				for (var entry : module.settings.entrySet()) {
					if (!settings.has(entry.getKey())) continue;
					// One outdated value must not discard the rest of the module.
					try { entry.getValue().set(settings.get(entry.getKey())); }
					catch (RuntimeException failure) { LoggerFactory.getLogger("ModelMirage").warn("Ignoring saved {}.{}: {}", module.id, entry.getKey(), failure.getMessage()); }
				}
			}
		} catch (IOException | RuntimeException failure) {
			LoggerFactory.getLogger("ModelMirage").error("Could not restore visual settings from {}", CONFIG, failure);
		}
	}

	/** Before 0.4 every HUD panel was a boolean of the Interface module; keep those choices. */
	private static void migratePanels(JsonObject config) {
		JsonObject interfaceModule = config.getAsJsonObject("Interface");
		if (interfaceModule == null || !interfaceModule.has("settings")) return;
		JsonObject old = interfaceModule.getAsJsonObject("settings");
		Map<String, String> panels = Map.of("logo", "Watermark", "infoHud", "Coordinates", "targetHud", "TargetHud", "effectHud", "Effects",
				"inventoryHud", "Inventory", "armorHud", "Armor", "arrayList", "ArrayList", "activeBinds", "Keybinds");
		boolean interfaceEnabled = !interfaceModule.has("enabled") || interfaceModule.get("enabled").getAsBoolean();
		panels.forEach((setting, module) -> {
			if (!old.has(setting) || config.has(module)) return;
			JsonObject migrated = new JsonObject();
			try { migrated.addProperty("enabled", interfaceEnabled && old.get(setting).getAsBoolean()); }
			catch (RuntimeException failure) { LoggerFactory.getLogger("ModelMirage").warn("Ignoring old HUD panel switch {}: {}", setting, failure.getMessage()); return; }
			config.add(module, migrated);
		});
		// Interface is no longer a switchable feature; a leftover binding could not be removed from the UI.
		interfaceModule.addProperty("enabled", true);
		interfaceModule.addProperty("bind", -1);
	}

	/**
	 * HUD scale, glass tint and icon colours used to be settings of an "Interface" module; they are global
	 * preferences now. appearance.json, loaded afterwards, wins once it has them.
	 */
	static void migrateInterface(JsonObject config) {
		JsonObject old = config.getAsJsonObject("Interface");
		if (old == null || !old.has("settings")) return;
		JsonObject settings = old.getAsJsonObject("settings");
		try {
			if (settings.has("hudScale")) VisualPreferences.hudScale = Math.clamp(settings.get("hudScale").getAsFloat(), 0.5f, 1.5f);
			if (settings.has("tint")) VisualPreferences.tint = Math.clamp(Math.round(settings.get("tint").getAsFloat() * 20) * 5f, 0, 90);
			if (settings.has("accentIcons")) VisualPreferences.accentIcons = settings.get("accentIcons").getAsBoolean();
		} catch (RuntimeException failure) {
			LoggerFactory.getLogger("ModelMirage").warn("Ignoring old interface settings: {}", failure.getMessage());
		}
	}

	/** The armor durability switch became a choice; "off" maps to hidden. */
	static void migrateArmor(JsonObject config) {
		JsonObject armor = config.getAsJsonObject("Armor");
		if (armor == null || !armor.has("settings")) return;
		JsonObject settings = armor.getAsJsonObject("settings");
		if (!settings.has("showDurability") || settings.has("durability")) return;
		try { if (!settings.get("showDurability").getAsBoolean()) settings.addProperty("durability", "hidden"); }
		catch (RuntimeException failure) { LoggerFactory.getLogger("ModelMirage").warn("Ignoring old armor durability switch: {}", failure.getMessage()); }
	}

	/** Older configs called the HUD mascot module "BoyKisser"; a saved "Mascot" entry wins. */
	static void migrateMascot(JsonObject config) {
		if (config.has("BoyKisser") && !config.has("Mascot")) config.add("Mascot", config.get("BoyKisser"));
	}

	private static final Map<String, Runnable> ACTIONS = new LinkedHashMap<>();

	/** Handler of an "action" setting, e.g. a reset button in a module card. */
	public static void action(String module, String setting, Runnable handler) { ACTIONS.put(module + "." + setting, handler); }

	public static void run(String module, String setting) {
		Runnable handler = ACTIONS.get(module + "." + setting);
		if (handler == null) throw new IllegalStateException("No handler for HUD action " + module + "." + setting);
		handler.run();
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
