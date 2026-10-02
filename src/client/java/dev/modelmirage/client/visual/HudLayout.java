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
			for (var entry : JsonParser.parseString(Files.readString(CONFIG)).getAsJsonObject().entrySet()) {
				JsonObject position = entry.getValue().getAsJsonObject();
				float x = position.get("x").getAsFloat();
				float y = position.get("y").getAsFloat();
				if (!Float.isFinite(x) || !Float.isFinite(y)) throw new IllegalArgumentException("Invalid HUD position");
				POSITIONS.put(entry.getKey(), new Position(Math.clamp(x, 0, 1), Math.clamp(y, 0, 1)));
			}
		} catch (IOException | RuntimeException failure) {
			LoggerFactory.getLogger("ModelMirage").error("Could not load HUD layout", failure);
		}
	}
	public static Position position(String id, float defaultX, float defaultY, int width, int height) {
		return POSITIONS.getOrDefault(id, new Position(defaultX / width, defaultY / height));
	}
	public static void move(String id, float x, float y, int width, int height) {
		POSITIONS.put(id, new Position(Math.clamp(x / width, 0, 1), Math.clamp(y / height, 0, 1)));
	}
	public static void save() throws IOException {
		JsonObject result = new JsonObject();
		POSITIONS.forEach((id, position) -> {
			JsonObject value = new JsonObject();
			value.addProperty("x", position.x());
			value.addProperty("y", position.y());
			result.add(id, value);
		});
		Files.createDirectories(CONFIG.getParent());
		Path temporary = Files.createTempFile(CONFIG.getParent(), "hud-", ".tmp");
		try { Files.writeString(temporary, result.toString()); Files.move(temporary, CONFIG, StandardCopyOption.REPLACE_EXISTING); }
		finally { Files.deleteIfExists(temporary); }
	}
	public record Position(float x, float y) { }
}
