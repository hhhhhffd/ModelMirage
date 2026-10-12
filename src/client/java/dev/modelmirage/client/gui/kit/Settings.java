package dev.modelmirage.client.gui.kit;

import com.google.gson.JsonPrimitive;
import dev.modelmirage.client.Lang;
import dev.modelmirage.client.visual.VisualModule;
import dev.modelmirage.client.visual.Visuals;

/** Builds rows and cards from visual module definitions, so menus and HUD popovers stay identical. */
public final class Settings {
	private Settings() { }

	public static Row row(VisualModule.Setting setting, Runnable changed) {
		Row row = control(setting, changed);
		if (setting.when != null) row.when = () -> Visuals.module(setting.moduleId).bool(setting.when);
		return row;
	}

	private static Row control(VisualModule.Setting setting, Runnable changed) {
		return switch (setting.type) {
			case "boolean" -> new ToggleRow(setting.name(), () -> setting.value.getAsBoolean(), value -> { setting.set(new JsonPrimitive(value)); changed.run(); });
			case "number" -> new SliderRow(setting.name(), setting.min, setting.max, setting.step, setting.unit,
					() -> setting.value.getAsDouble(), value -> { setting.set(new JsonPrimitive(value)); changed.run(); });
			case "color" -> new ColorRow(setting.name(), () -> setting.value.getAsInt(), value -> { setting.set(new JsonPrimitive(value)); changed.run(); });
			case "choice" -> new ChoiceRow(setting.name(), setting.options, () -> setting.value.getAsString(), value -> { setting.set(new JsonPrimitive(value)); changed.run(); });
			default -> throw new IllegalArgumentException("Unsupported setting type " + setting.type);
		};
	}

	/** Card with the module switch in its header, one row per setting and a key binding row. */
	public static Group module(VisualModule module, Runnable changed) {
		Group group = new Group().title(module.name()).toggle(() -> module.enabled, value -> { module.enabled = value; changed.run(); });
		// Orders are edited by dragging the elements on the HUD itself.
		for (VisualModule.Setting setting : module.settings.values()) if (!setting.type.equals("order")) group.add(row(setting, changed));
		group.add(new KeyRow(Lang.tr("modelmirage.row.key"), () -> module.bind, key -> { module.bind = key; changed.run(); }));
		return group;
	}
}
