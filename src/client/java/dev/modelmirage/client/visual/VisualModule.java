package dev.modelmirage.client.visual;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
	public int color(String id) { return setting(id).value.getAsInt(); }
	public String choice(String id) { return setting(id).value.getAsString(); }
	/** HUD panels are modules too, but they are configured from the HUD page rather than listed as features. */
	public boolean isPanel() { return category.equals("panel"); }

	public static final class Setting {
		public final String id;
		public final String name;
		public final String type;
		public final double min;
		public final double max;
		public final double step;
		public final String unit;
		public final List<Option> options = new ArrayList<>();
		public volatile JsonElement value;

		Setting(JsonObject data) {
			id = data.get("id").getAsString();
			name = data.get("name").getAsString();
			type = data.get("type").getAsString();
			min = data.has("min") ? data.get("min").getAsDouble() : 0;
			max = data.has("max") ? data.get("max").getAsDouble() : 1;
			step = data.has("step") ? data.get("step").getAsDouble() : 1;
			unit = data.has("unit") ? data.get("unit").getAsString() : "";
			if (data.has("options")) for (JsonElement option : data.getAsJsonArray("options")) {
				JsonObject entry = option.getAsJsonObject();
				options.add(new Option(entry.get("id").getAsString(), entry.get("name").getAsString()));
			}
			if (type.equals("choice") && options.isEmpty()) throw new IllegalArgumentException("Choice setting without options " + id);
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
				case "color" -> new JsonPrimitive(input.getAsInt());
				case "choice" -> {
					String id = input.getAsString();
					if (options.stream().noneMatch(option -> option.id().equals(id))) throw new IllegalArgumentException("Unknown option " + id + " for " + this.id);
					yield new JsonPrimitive(id);
				}
				default -> throw new IllegalArgumentException("Unsupported HUD control " + type);
			};
		}
	}

	public record Option(String id, String name) { }
}
