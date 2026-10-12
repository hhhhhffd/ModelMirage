package dev.modelmirage.client.visual;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.modelmirage.client.Lang;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HUD controls shared by the renderer and menu. */
public final class VisualModule {
	public final String id;
	public final String category;
	public final Map<String, Setting> settings = new LinkedHashMap<>();
	public volatile boolean enabled;
	public int bind = -1;

	VisualModule(JsonObject definition) {
		id = definition.get("id").getAsString();
		category = definition.get("category").getAsString();
		enabled = definition.has("enabled") && definition.get("enabled").getAsBoolean();
		for (JsonElement value : definition.getAsJsonArray("settings")) {
			Setting setting = new Setting(id, value.getAsJsonObject());
			if (settings.put(setting.id, setting) != null) throw new IllegalArgumentException("Duplicate HUD setting " + setting.id);
		}
	}

	/** Names come from the lang files: modelmirage.module.<id>, .<id>.<setting> and .<id>.<setting>.<option>. */
	public String name() { return Lang.tr("modelmirage.module." + id); }

	public Setting setting(String id) {
		Setting result = settings.get(id);
		if (result == null) throw new IllegalArgumentException("Unknown setting " + this.id + "." + id);
		return result;
	}

	public boolean bool(String id) { return setting(id).value.getAsBoolean(); }
	public float number(String id) { return setting(id).value.getAsFloat(); }
	public int color(String id) { return setting(id).value.getAsInt(); }
	public String choice(String id) { return setting(id).value.getAsString(); }
	/** Items of an "order" setting, e.g. the watermark elements from left to right. */
	public List<String> order(String id) { return List.of(setting(id).value.getAsString().split(",")); }
	/** HUD panels are modules too, but they are configured from the HUD page rather than listed as features. */
	public boolean isPanel() { return category.equals("panel"); }

	public static final class Setting {
		public final String id;
		public final String moduleId;
		public final String type;
		public final double min;
		public final double max;
		public final double step;
		public final String unit;
		/** Boolean setting of the same module that has to be on for this row to show; null when always shown. */
		public final String when;
		/** Settings sharing a group are shown as one checklist row instead of one switch each. */
		public final String group;
		public final List<Option> options = new ArrayList<>();
		private final List<String> orderItems;
		public volatile JsonElement value;

		Setting(String moduleId, JsonObject data) {
			this.moduleId = moduleId;
			id = data.get("id").getAsString();
			type = data.get("type").getAsString();
			min = data.has("min") ? data.get("min").getAsDouble() : 0;
			max = data.has("max") ? data.get("max").getAsDouble() : 1;
			step = data.has("step") ? data.get("step").getAsDouble() : 1;
			unit = data.has("unit") ? data.get("unit").getAsString() : "";
			when = data.has("when") ? data.get("when").getAsString() : null;
			group = data.has("group") ? data.get("group").getAsString() : null;
			orderItems = type.equals("order") ? List.of(data.get("value").getAsString().split(",")) : List.of();
			if (data.has("options")) for (JsonElement option : data.getAsJsonArray("options")) {
				JsonObject entry = option.getAsJsonObject();
				options.add(new Option(entry.get("id").getAsString(), "modelmirage.module." + moduleId + "." + id + "." + entry.get("id").getAsString()));
			}
			if (type.equals("choice") && options.isEmpty()) throw new IllegalArgumentException("Choice setting without options " + id);
			if (!(min < max) || !(step > 0) || !Double.isFinite(min) || !Double.isFinite(max) || !Double.isFinite(step)) {
				throw new IllegalArgumentException("Invalid HUD slider range " + id);
			}
			set(data.get("value"));
		}

		public String name() { return Lang.tr("modelmirage.module." + moduleId + "." + id); }

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
				case "order" -> {
					// Known items in the saved order; unknown ones are dropped and missing ones keep their default place at the end.
					List<String> result = new ArrayList<>();
					for (String item : input.getAsString().split(",")) if (orderItems.contains(item) && !result.contains(item)) result.add(item);
					for (String item : orderItems) if (!result.contains(item)) result.add(item);
					yield new JsonPrimitive(String.join(",", result));
				}
				default -> throw new IllegalArgumentException("Unsupported HUD control " + type);
			};
		}
	}

	public record Option(String id, String key) {
		public String name() { return Lang.tr(key); }
	}
}
