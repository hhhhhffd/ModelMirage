package dev.modelmirage.client.gui.kit;

import dev.modelmirage.client.gui.MirageUi;
import dev.modelmirage.client.visual.VisualModule;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.input.KeyEvent;

/** Dropdown selector: a pill with the current option that opens a list popup. */
public final class ChoiceRow extends Row {
	private static final float ITEM = 20;
	private final List<VisualModule.Option> options;
	private final Supplier<String> value;
	private final Consumer<String> changed;
	private final Menu menu = new Menu();
	private float open;

	public ChoiceRow(String label, List<VisualModule.Option> options, Supplier<String> value, Consumer<String> changed) {
		super(label);
		this.options = List.copyOf(options);
		this.value = value;
		this.changed = changed;
	}

	private String current() {
		String id = value.get();
		return options.stream().filter(option -> option.id().equals(id)).map(VisualModule.Option::name).findFirst().orElse(id);
	}

	private float pillWidth() { return (float) Math.min(width * 0.5f - 12, Math.max(70, options.stream().mapToDouble(option -> MirageUi.MEDIUM.width(option.name(), Theme.CAPTION + 0.5f)).max().orElse(40) + 28)); }

	@Override
	protected float labelWidth() { return width - pillWidth() - 30; }

	@Override
	protected void renderControl(Surface surface) {
		var graphics = surface.graphics;
		open = MirageUi.animate(open, surface.isOpen(menu) ? 1 : 0, 20, surface.delta);
		float pill = pillWidth(), left = right() - pill, cy = centerY();
		int fill = MirageUi.mix(Theme.CONTROL, Theme.CONTROL_HOVER, Math.max(hover, open));
		MirageUi.shape(graphics, left, cy - 8, pill, 16, Theme.RADIUS_CONTROL, 0, fill, fill);
		String text = MirageUi.MEDIUM.fit(current(), Theme.CAPTION + 0.5f, pill - 26);
		MirageUi.MEDIUM.draw(graphics, text, left + 8, cy - (Theme.CAPTION + 0.5f) / 2 - 0.5f, Theme.CAPTION + 0.5f, Theme.TEXT);
		Paint.chevron(graphics, right() - 9, cy + 0.5f, open, Theme.TEXT_SECONDARY);
	}

	@Override
	public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) {
		if (button != 0 || !contains(mouseX, mouseY)) return false;
		surface.focus(this);
		toggle(surface);
		return true;
	}

	@Override
	public boolean keyPressed(Surface surface, KeyEvent event) {
		if (event.isConfirmation() || event.isSelection()) { toggle(surface); return true; }
		int direction = event.isUp() ? -1 : event.isDown() ? 1 : 0;
		if (direction == 0) return false;
		int index = options.stream().map(VisualModule.Option::id).toList().indexOf(value.get());
		changed.accept(options.get(Math.floorMod(index + direction, options.size())).id());
		return true;
	}

	private void toggle(Surface surface) {
		if (surface.isOpen(menu)) { surface.close(); return; }
		float pill = pillWidth();
		menu.opening = 0;
		menu.anchor(surface, right() - pill, centerY() - 8, pill, 16, Math.max(pill, 110), options.size() * ITEM + 8);
		surface.open(menu);
	}

	private final class Menu extends Popup {
		@Override
		protected void renderContent(Surface surface) {
			var graphics = surface.graphics;
			String selected = value.get();
			for (int i = 0; i < options.size(); i++) {
				var option = options.get(i);
				float top = y + 4 + i * ITEM;
				if (over(surface, x + 4, top, width - 8, ITEM)) MirageUi.shape(graphics, x + 4, top, width - 8, ITEM, 5, 0, Theme.PRESSED, Theme.PRESSED);
				boolean chosen = option.id().equals(selected);
				MirageUi.MEDIUM.draw(graphics, MirageUi.MEDIUM.fit(option.name(), Theme.BODY, width - 34), x + 12, Paint.centeredTextY(top, ITEM, Theme.BODY), Theme.BODY, chosen ? Theme.accent() : Theme.TEXT);
				if (chosen) MirageUi.shape(graphics, x + width - 16, top + ITEM / 2 - 2.5f, 5, 5, 2.5f, 0, Theme.accent(), Theme.accent());
			}
		}

		@Override
		public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) {
			int index = (int) Math.floor((mouseY - y - 4) / ITEM);
			if (button == 0 && index >= 0 && index < options.size()) {
				changed.accept(options.get(index).id());
				surface.close();
			}
			return true;
		}
	}
}
