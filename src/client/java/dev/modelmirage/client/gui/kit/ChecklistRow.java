package dev.modelmirage.client.gui.kit;

import dev.modelmirage.client.Lang;
import dev.modelmirage.client.gui.MirageUi;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** "Select ›" row that opens a checklist; clicking an entry flips it and keeps the list open. */
public final class ChecklistRow extends Row {
	private static final float ITEM = 18;
	/** One switch shown as a checklist entry. */
	public record Entry(String name, Supplier<Boolean> value, Consumer<Boolean> changed) { }
	private final List<Entry> entries;
	private final Menu menu = new Menu();
	private float open;

	public ChecklistRow(String label, List<Entry> entries) {
		super(label);
		this.entries = List.copyOf(entries);
	}

	private String summary() {
		long chosen = entries.stream().filter(entry -> entry.value().get()).count();
		return Lang.tr("modelmirage.row.checklist", chosen, entries.size());
	}

	private float pillWidth() { return Math.max(70, MirageUi.MEDIUM.width(summary(), Theme.CAPTION + 0.5f) + 28); }

	@Override
	protected float labelWidth() { return width - pillWidth() - 30; }

	@Override
	protected void renderControl(Surface surface) {
		var graphics = surface.graphics;
		open = MirageUi.animate(open, surface.isOpen(menu) ? 1 : 0, 20, surface.delta);
		float pill = pillWidth(), left = right() - pill, cy = centerY();
		int fill = MirageUi.mix(Theme.CONTROL, Theme.CONTROL_HOVER, Math.max(hover, open));
		MirageUi.shape(graphics, left, cy - 8, pill, 16, Theme.RADIUS_CONTROL, 0, fill, fill);
		MirageUi.MEDIUM.draw(graphics, summary(), left + 8, cy - (Theme.CAPTION + 0.5f) / 2 - 0.5f, Theme.CAPTION + 0.5f, Theme.TEXT);
		// A chevron pointing right: the list opens beside the card.
		Paint.chevron(graphics, right() - 9, cy + 0.5f, -0.5f, Theme.TEXT_SECONDARY);
	}

	@Override
	public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) {
		if (button != 0 || !contains(mouseX, mouseY)) return false;
		if (surface.isOpen(menu)) { surface.close(); return true; }
		float listWidth = 10 + 24 + (float) entries.stream().mapToDouble(entry -> MirageUi.MEDIUM.width(entry.name(), Theme.BODY)).max().orElse(60) + 12;
		float listHeight = entries.size() * ITEM + 8;
		menu.opening = 0;
		menu.width = listWidth;
		menu.height = listHeight;
		// Beside the card when there is room, otherwise below the row.
		if (right() + 8 + listWidth <= surface.viewWidth - 6) {
			menu.x = right() + 8;
			menu.y = Math.clamp(y - 4, 6, Math.max(6, surface.viewHeight - listHeight - 6));
		} else menu.anchorTo(surface, right() - pillWidth(), centerY() - 8, pillWidth(), 16, listWidth, listHeight);
		surface.open(menu);
		return true;
	}

	private final class Menu extends Popup {
		void anchorTo(Surface surface, float ax, float ay, float aw, float ah, float w, float h) { anchor(surface, ax, ay, aw, ah, w, h); }

		@Override
		protected void renderContent(Surface surface) {
			var graphics = surface.graphics;
			for (int i = 0; i < entries.size(); i++) {
				Entry entry = entries.get(i);
				float top = y + 4 + i * ITEM;
				if (over(surface, x + 4, top, width - 8, ITEM)) MirageUi.shape(graphics, x + 4, top, width - 8, ITEM, 5, 0, Theme.PRESSED, Theme.PRESSED);
				boolean on = entry.value().get();
				if (on) {
					// Check mark from two strokes.
					float cx = x + 14, cy = top + ITEM / 2;
					graphics.pose().pushMatrix();
					graphics.pose().translate(cx, cy);
					graphics.pose().rotate((float) Math.toRadians(45));
					MirageUi.shape(graphics, -1.2f, -4, 1.4f, 6.5f, 0.7f, 0, Theme.accent(), Theme.accent());
					MirageUi.shape(graphics, -3.5f, 1.1f, 3.6f, 1.4f, 0.7f, 0, Theme.accent(), Theme.accent());
					graphics.pose().popMatrix();
				}
				MirageUi.MEDIUM.draw(graphics, entry.name(), x + 28, Paint.centeredTextY(top, ITEM, Theme.BODY), Theme.BODY, on ? Theme.TEXT : Theme.TEXT_SECONDARY);
			}
		}

		@Override
		public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) {
			int index = (int) Math.floor((mouseY - y - 4) / ITEM);
			if (button == 0 && index >= 0 && index < entries.size()) {
				Entry entry = entries.get(index);
				entry.changed().accept(!entry.value().get());
			}
			return true;
		}
	}
}
