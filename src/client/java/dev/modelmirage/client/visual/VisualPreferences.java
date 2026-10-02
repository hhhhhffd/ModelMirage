package dev.modelmirage.client.visual;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;
import dev.modelmirage.client.gui.AstolfoUi;
import org.slf4j.LoggerFactory;

public final class VisualPreferences {
	private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("modelmirage/appearance.json");
	public static float guiScale = 1;
	public static float opacity = 1;
	public static boolean animations = true;
	private VisualPreferences() { }
	public static void load() {
		if (!Files.exists(CONFIG)) return;
		try {
			JsonObject data = JsonParser.parseString(Files.readString(CONFIG)).getAsJsonObject();
			AstolfoUi.ACCENT = 0xFF000000 | data.get("accent").getAsInt() & 0xFFFFFF;
			guiScale = bounded(data.get("scale").getAsFloat(), 0.75f, 1.5f);
			opacity = bounded(data.get("opacity").getAsFloat(), 0.65f, 1);
			animations = data.get("animations").getAsBoolean();
		} catch (IOException | RuntimeException failure) { LoggerFactory.getLogger("ModelMirage").error("Could not restore appearance settings", failure); }
	}
	private static float bounded(float value, float min, float max) {
		if (!Float.isFinite(value)) throw new IllegalArgumentException("Invalid appearance setting");
		return Math.clamp(value, min, max);
	}
	public static void save() throws IOException {
		JsonObject data = new JsonObject();
		data.addProperty("accent", AstolfoUi.ACCENT); data.addProperty("scale", guiScale); data.addProperty("opacity", opacity);
		data.addProperty("animations", animations);
		Files.createDirectories(CONFIG.getParent());
		Path temporary = Files.createTempFile(CONFIG.getParent(), "appearance-", ".tmp");
		try { Files.writeString(temporary, data.toString()); Files.move(temporary, CONFIG, StandardCopyOption.REPLACE_EXISTING); }
		finally { Files.deleteIfExists(temporary); }
	}
}
