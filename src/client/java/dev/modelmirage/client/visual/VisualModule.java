package dev.modelmirage.client.visual;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.Map;

/** HUD controls shared by the renderer and menu. */
public final class VisualModule {
	public final String id;
	public final String category;
	public final String name;
	public final Map<String, Setting> settings = new LinkedHashMap<>();
	public volatile boolean enabled;
	public int bind = -1;

	VisualModule(JsonObject definition) {
		id = definition.get("id").getAsString();
		name = definition.get("name").getAsString();
		category = definition.get("category").getAsString();
		enabled = definition.has("enabled") && definition.get("enabled").getAsBoolean();
		for (JsonElement value : definition.getAsJsonArray("settings")) {
			Setting setting = new Setting(value.getAsJsonObject());
			if (settings.put(setting.id, setting) != null) throw new IllegalArgumentException("Duplicate HUD setting " + setting.id);
		}
	}

	public Setting setting(String id) {
		Setting result = settings.get(id);
		if (result == null) throw new IllegalArgumentException("Unknown setting " + this.id + "." + id);
		return result;
	}

	public boolean bool(String id) { return setting(id).value.getAsBoolean(); }
	public float number(String id) { return setting(id).value.getAsFloat(); }

	public static final class Setting {
		public final String id;
		public final String name;
		public final String type;
		public final double min;
		public final double max;
		public final double step;
		public volatile JsonElement value;

		Setting(JsonObject data) {
			id = data.get("id").getAsString();
			name = data.get("name").getAsString();
			type = data.get("type").getAsString();
			min = data.has("min") ? data.get("min").getAsDouble() : 0;
			max = data.has("max") ? data.get("max").getAsDouble() : 1;
			step = data.has("step") ? data.get("step").getAsDouble() : 1;
			if (!(min < max) || !(step > 0) || !Double.isFinite(min) || !Double.isFinite(max) || !Double.isFinite(step)) {
				throw new IllegalArgumentException("Invalid HUD slider range " + id);
			}
			set(data.get("value"));
		}

		public void set(JsonElement input) {
			value = switch (type) {
				case "boolean" -> new JsonPrimitive(input.getAsBoolean());
				case "number" -> {
					double number = input.getAsDouble();
					if (!Double.isFinite(number)) throw new IllegalArgumentException("Non-finite HUD setting");
					number = Math.clamp(number, min, max);
					yield new JsonPrimitive(Math.clamp(min + Math.round((number - min) / step) * step, min, max));
				}
				default -> throw new IllegalArgumentException("Unsupported HUD control " + type);
			};
		}
	}
}
