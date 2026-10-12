package dev.modelmirage.client.visual;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

/**
 * Saved panel placements. x and y are shares of the space left around the panel (0 = left/top edge,
 * 1 = right/bottom edge), so a panel resting on an edge or in the centre stays there at any window size.
 */
public final class HudLayout {
	private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("modelmirage/hud.json");
	private static final int VERSION = 2;
	private static final Map<String, Position> POSITIONS = new LinkedHashMap<>();
	private HudLayout() { }
	public static void load() {
		if (!Files.exists(CONFIG)) return;
		try {
			POSITIONS.putAll(parse(Files.readString(CONFIG)));
		} catch (IOException | RuntimeException failure) {
			LoggerFactory.getLogger("ModelMirage").error("Could not load HUD layout", failure);
		}
	}

	/** Positions are clamped to 0–1 and scale to 0.5–2; any invalid entry rejects the file. Files without a version, and entries marked legacy, use the old meaning. */
	static Map<String, Position> parse(String json) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		// Before version 2, x and y were the top-left corner as a share of the whole window.
		boolean legacy = !root.has("version") || root.get("version").getAsInt() < VERSION;
		Map<String, Position> result = new LinkedHashMap<>();
		for (var entry : root.entrySet()) {
			if (entry.getKey().equals("version")) continue;
			JsonObject position = entry.getValue().getAsJsonObject();
			float x = position.get("x").getAsFloat();
			float y = position.get("y").getAsFloat();
			float scale = position.has("scale") ? position.get("scale").getAsFloat() : 1;
			if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(scale)) throw new IllegalArgumentException("Invalid HUD position");
			// Older layouts saved the mascot panel as "boykisser".
			String id = entry.getKey().equals("boykisser") ? "mascot" : entry.getKey();
			if (!id.equals(entry.getKey()) && result.containsKey(id)) continue;
			boolean old = legacy || position.has("legacy") && position.get("legacy").getAsBoolean();
			result.put(id, new Position(Math.clamp(x, 0, 1), Math.clamp(y, 0, 1), Math.clamp(scale, 0.5f, 2), old));
		}
		return result;
	}

	/** The saved placement, or null while the panel sits at its default spot. */
	public static Position get(String id) { return POSITIONS.get(id); }
	public static void place(String id, float x, float y) {
		Position previous = POSITIONS.get(id);
		POSITIONS.put(id, new Position(Math.clamp(x, 0, 1), Math.clamp(y, 0, 1), previous == null ? 1 : previous.scale(), false));
	}
	public static void scale(String id, float scale) {
		Position previous = POSITIONS.get(id);
		if (previous == null) throw new IllegalArgumentException("HUD panel must be positioned before resizing");
		POSITIONS.put(id, new Position(previous.x(), previous.y(), Math.clamp(scale, 0.5f, 2), previous.legacy()));
	}
	public static void reset(String id) { POSITIONS.remove(id); }
	public static Map<String, Position> snapshot() { return new LinkedHashMap<>(POSITIONS); }
	public static void restore(Map<String, Position> positions) { POSITIONS.clear(); POSITIONS.putAll(positions); }
	public static void save() throws IOException {
		JsonObject result = new JsonObject();
		result.addProperty("version", VERSION);
		POSITIONS.forEach((id, position) -> {
			JsonObject value = new JsonObject();
			// A legacy entry not drawn since loading keeps its old meaning; it is converted the first time it is shown.
			if (position.legacy()) value.addProperty("legacy", true);
			value.addProperty("x", position.x());
			value.addProperty("y", position.y());
			value.addProperty("scale", position.scale());
			result.add(id, value);
		});
		Files.createDirectories(CONFIG.getParent());
		Path temporary = Files.createTempFile(CONFIG.getParent(), "hud-", ".tmp");
		try { Files.writeString(temporary, result.toString()); Files.move(temporary, CONFIG, StandardCopyOption.REPLACE_EXISTING); }
		finally { Files.deleteIfExists(temporary); }
	}
	public record Position(float x, float y, float scale, boolean legacy) { }
}
