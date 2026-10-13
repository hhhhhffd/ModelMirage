package dev.modelmirage.client.gui.kit;

import dev.modelmirage.client.Lang;
import dev.modelmirage.client.gui.MirageUi;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * "Select ›" row that opens a checklist; clicking an entry flips it and keeps the list open. Entries with options
 * of their own unfold them below on a right click (or a click on their chevron).
 */
public final class ChecklistRow extends Row {
	private static final float ITEM = 18, INDENT = 14, CHEVRON = 16;
	/** One switch shown as a checklist entry, with the switches that refine it. */
	public record Entry(String name, Supplier<Boolean> value, Consumer<Boolean> changed, List<Entry> options) {
		public Entry(String name, Supplier<Boolean> value, Consumer<Boolean> changed) { this(name, value, changed, List.of()); }
	}
	/** A visible line of the open list: an entry, or one of its options when {@code parent} is set. */
	private record Line(Entry entry, Entry parent) { }
	private final List<Entry> entries;
	private final Set<Entry> unfolded = new HashSet<>();
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
		float listWidth = 10 + 24 + (float) entries.stream().flatMap(entry -> Stream.concat(Stream.of(entry), entry.options().stream()))
				.mapToDouble(entry -> MirageUi.MEDIUM.width(entry.name(), Theme.BODY) + (entry.options().isEmpty() ? 0 : CHEVRON)).max().orElse(60) + INDENT + 12;
		float listHeight = lines().size() * ITEM + 8;
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

	/** Entries in order, each followed by its options while unfolded. */
	private List<Line> lines() {
		List<Line> lines = new ArrayList<>();
		for (Entry entry : entries) {
			lines.add(new Line(entry, null));
			if (unfolded.contains(entry)) for (Entry option : entry.options()) lines.add(new Line(option, entry));
		}
		return lines;
	}

	private final class Menu extends Popup {
		void anchorTo(Surface surface, float ax, float ay, float aw, float ah, float w, float h) { anchor(surface, ax, ay, aw, ah, w, h); }

		@Override
		protected void renderContent(Surface surface) {
			var graphics = surface.graphics;
			List<Line> lines = lines();
			for (int i = 0; i < lines.size(); i++) {
				Line line = lines.get(i);
				Entry entry = line.entry();
				float top = y + 4 + i * ITEM, left = x + (line.parent() == null ? 0 : INDENT);
				if (over(surface, x + 4, top, width - 8, ITEM)) MirageUi.shape(graphics, x + 4, top, width - 8, ITEM, 5, 0, Theme.PRESSED, Theme.PRESSED);
				// Options of a switched-off entry have no effect, so they are dimmed.
				boolean active = line.parent() == null || line.parent().value().get();
				boolean on = entry.value().get();
				if (on) {
					// Check mark from two strokes.
					int mark = active ? Theme.accent() : Theme.TEXT_TERTIARY;
					float cx = left + 14, cy = top + ITEM / 2;
					graphics.pose().pushMatrix();
					graphics.pose().translate(cx, cy);
					graphics.pose().rotate((float) Math.toRadians(45));
					MirageUi.shape(graphics, -1.2f, -4, 1.4f, 6.5f, 0.7f, 0, mark, mark);
					MirageUi.shape(graphics, -3.5f, 1.1f, 3.6f, 1.4f, 0.7f, 0, mark, mark);
					graphics.pose().popMatrix();
				}
				MirageUi.MEDIUM.draw(graphics, entry.name(), left + 28, Paint.centeredTextY(top, ITEM, Theme.BODY), Theme.BODY,
						on && active ? Theme.TEXT : active ? Theme.TEXT_SECONDARY : Theme.TEXT_TERTIARY);
				if (!entry.options().isEmpty()) Paint.chevron(graphics, x + width - 12, top + ITEM / 2 + 0.5f, unfolded.contains(entry) ? 0 : -0.5f, Theme.TEXT_SECONDARY);
			}
		}

		@Override
		public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) {
			List<Line> lines = lines();
			int index = (int) Math.floor((mouseY - y - 4) / ITEM);
			if (index < 0 || index >= lines.size()) return true;
			Entry entry = lines.get(index).entry();
			boolean chevron = !entry.options().isEmpty() && mouseX >= x + width - CHEVRON - 6;
			if (button == 1 || button == 0 && chevron) {
				if (entry.options().isEmpty()) return true;
				if (!unfolded.remove(entry)) unfolded.add(entry);
				height = lines().size() * ITEM + 8;
				// Keep the grown list inside the view.
				y = Math.clamp(y, 6, Math.max(6, surface.viewHeight - height - 6));
			} else if (button == 0) entry.changed().accept(!entry.value().get());
			return true;
		}
	}
}
