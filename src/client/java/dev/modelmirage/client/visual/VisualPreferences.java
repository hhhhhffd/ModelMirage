package dev.modelmirage.client.visual;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;
import dev.modelmirage.client.gui.MirageUi;
import org.slf4j.LoggerFactory;

public final class VisualPreferences {
	private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("modelmirage/appearance.json");
	public static float guiScale = 1;
	public static float opacity = 1;
	public static boolean animations = true;
	/** Glass blur of the menu and of every HUD panel without its own glass settings. */
	public static float blur = 16;
	/** Darkening of HUD glass in percent; panels may override it. */
	public static float tint = 35;
	public static boolean hudEnabled = true;
	public static float hudScale = 0.65f;
	public static boolean accentIcons = true;
	private VisualPreferences() { }
	public static void load() {
		if (!Files.exists(CONFIG)) return;
		try {
			JsonObject data = JsonParser.parseString(Files.readString(CONFIG)).getAsJsonObject();
			MirageUi.ACCENT = 0xFF000000 | data.get("accent").getAsInt() & 0xFFFFFF;
			guiScale = bounded(data.get("scale").getAsFloat(), 0.75f, 1.5f);
			opacity = bounded(data.get("opacity").getAsFloat(), 0, 1);
			animations = data.get("animations").getAsBoolean();
			if (data.has("blur")) blur = bounded(data.get("blur").getAsFloat(), 0, 30);
			// HUD keys were added in 0.4; older files keep the values migrated from visuals.json.
			if (data.has("tint")) tint = bounded(data.get("tint").getAsFloat(), 0, 90);
			if (data.has("hudEnabled")) hudEnabled = data.get("hudEnabled").getAsBoolean();
			if (data.has("hudScale")) hudScale = bounded(data.get("hudScale").getAsFloat(), 0.5f, 1.5f);
			if (data.has("accentIcons")) accentIcons = data.get("accentIcons").getAsBoolean();
		} catch (IOException | RuntimeException failure) { LoggerFactory.getLogger("ModelMirage").error("Could not restore appearance settings", failure); }
	}
	private static float bounded(float value, float min, float max) {
		if (!Float.isFinite(value)) throw new IllegalArgumentException("Invalid appearance setting");
		return Math.clamp(value, min, max);
	}
	public static void save() throws IOException {
		JsonObject data = new JsonObject();
		data.addProperty("accent", MirageUi.ACCENT); data.addProperty("scale", guiScale); data.addProperty("opacity", opacity);
		data.addProperty("animations", animations); data.addProperty("blur", blur); data.addProperty("tint", tint);
		data.addProperty("hudEnabled", hudEnabled); data.addProperty("hudScale", hudScale); data.addProperty("accentIcons", accentIcons);
		Files.createDirectories(CONFIG.getParent());
		Path temporary = Files.createTempFile(CONFIG.getParent(), "appearance-", ".tmp");
		try { Files.writeString(temporary, data.toString()); Files.move(temporary, CONFIG, StandardCopyOption.REPLACE_EXISTING); }
		finally { Files.deleteIfExists(temporary); }
	}
}
