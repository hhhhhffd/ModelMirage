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

public final class HudLayout {
	private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("modelmirage/hud.json");
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
	/** Positions are clamped into the window and scale into 0.5–2; any invalid entry rejects the file. */
	static Map<String, Position> parse(String json) {
		Map<String, Position> result = new LinkedHashMap<>();
		for (var entry : JsonParser.parseString(json).getAsJsonObject().entrySet()) {
			JsonObject position = entry.getValue().getAsJsonObject();
			float x = position.get("x").getAsFloat();
			float y = position.get("y").getAsFloat();
			float scale = position.has("scale") ? position.get("scale").getAsFloat() : 1;
			if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(scale)) throw new IllegalArgumentException("Invalid HUD position");
			// Older layouts saved the mascot panel as "boykisser".
			String id = entry.getKey().equals("boykisser") ? "mascot" : entry.getKey();
			if (!id.equals(entry.getKey()) && result.containsKey(id)) continue;
			result.put(id, new Position(Math.clamp(x, 0, 1), Math.clamp(y, 0, 1), Math.clamp(scale, 0.5f, 2)));
		}
		return result;
	}

	public static Position position(String id, float defaultX, float defaultY, int width, int height) {
		return POSITIONS.getOrDefault(id, new Position(defaultX / width, defaultY / height, 1));
	}
	public static void move(String id, float x, float y, int width, int height) {
		float scale = POSITIONS.containsKey(id) ? POSITIONS.get(id).scale() : 1;
		POSITIONS.put(id, new Position(Math.clamp(x / width, 0, 1), Math.clamp(y / height, 0, 1), scale));
	}
	public static void scale(String id, float scale) {
		Position previous = POSITIONS.get(id);
		if (previous == null) throw new IllegalArgumentException("HUD panel must be positioned before resizing");
		POSITIONS.put(id, new Position(previous.x(), previous.y(), Math.clamp(scale, 0.5f, 2)));
	}
	public static void reset(String id) { POSITIONS.remove(id); }
	public static Map<String, Position> snapshot() { return new LinkedHashMap<>(POSITIONS); }
	public static void restore(Map<String, Position> positions) { POSITIONS.clear(); POSITIONS.putAll(positions); }
	public static void save() throws IOException {
		JsonObject result = new JsonObject();
		POSITIONS.forEach((id, position) -> {
			JsonObject value = new JsonObject();
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
	public record Position(float x, float y, float scale) { }
}
