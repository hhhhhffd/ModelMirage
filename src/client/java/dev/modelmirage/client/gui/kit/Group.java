package dev.modelmirage.client.gui.kit;

import dev.modelmirage.client.gui.AstolfoUi;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Settings card: optional caption above it (icon + small caps), optional header with a title and a
 * master switch, then rows separated by hairlines. Rows dim while the master switch is off.
 */
public final class Group extends Control {
	private static final float CAPTION_HEIGHT = 18, HEADER_HEIGHT = 28;
	public final List<Control> rows = new ArrayList<>();
	public String caption = "", captionIcon = "", title = "";
	public BooleanSupplier enabled;
	public Consumer<Boolean> onToggle;
	private float progress = -1, headerHover, dim = 1;

	public Group title(String title) { this.title = title; return this; }
	public Group caption(String icon, String caption) { this.captionIcon = icon; this.caption = caption; return this; }
	public Group toggle(BooleanSupplier enabled, Consumer<Boolean> onToggle) { this.enabled = enabled; this.onToggle = onToggle; return this; }
	public Group add(Control row) { rows.add(row); return this; }

	private float captionHeight() { return caption.isEmpty() ? 0 : CAPTION_HEIGHT; }
	private float headerHeight() { return title.isEmpty() ? 0 : HEADER_HEIGHT; }
	public float cardTop() { return y + captionHeight(); }

	@Override
	public float layout(float x, float y, float width) {
		this.x = x;
		this.y = y;
		this.width = width;
		float cursor = y + captionHeight() + headerHeight() + (rows.isEmpty() ? 0 : 3);
		for (Control row : rows) {
			if (!row.visible) continue;
			cursor += row.layout(x, cursor, width);
		}
		height = cursor - y + (rows.isEmpty() ? 0 : 3);
		return height;
	}

	@Override
	public void render(Surface surface) {
		if (!visible) return;
		var graphics = surface.graphics;
		if (!caption.isEmpty()) {
			float left = x + 2;
			if (!captionIcon.isEmpty()) {
				AstolfoUi.ICONS.draw(graphics, captionIcon, left, y + 4, 8, Theme.TEXT_TERTIARY);
				left += 12;
			}
			AstolfoUi.SEMIBOLD.draw(graphics, caption.toUpperCase(java.util.Locale.ROOT), left, y + 5, Theme.CAPTION, Theme.TEXT_TERTIARY);
		}
		float top = cardTop();
		AstolfoUi.shape(graphics, x, top, width, height - captionHeight(), Theme.RADIUS_CARD, 0, Theme.CARD, Theme.CARD);
		AstolfoUi.shape(graphics, x, top, width, height - captionHeight(), Theme.RADIUS_CARD, 0.8f, Theme.CARD_STROKE, 0x0AFFFFFF);
		boolean on = enabled == null || enabled.getAsBoolean();
		if (!title.isEmpty()) {
			boolean over = surface.mouseX >= x && surface.mouseX < x + width && surface.mouseY >= top && surface.mouseY < top + HEADER_HEIGHT && !surface.blockedByPopup(surface.mouseX, surface.mouseY);
			headerHover = AstolfoUi.animate(headerHover, over ? 1 : 0, 18, surface.delta);
			float titleWidth = width - (enabled == null ? 24 : 60);
			AstolfoUi.SEMIBOLD.draw(graphics, AstolfoUi.SEMIBOLD.fit(title, Theme.HEADLINE, titleWidth), x + 12, Paint.centeredTextY(top, HEADER_HEIGHT, Theme.HEADLINE), Theme.HEADLINE, Theme.TEXT);
			if (enabled != null) {
				progress = progress < 0 ? (on ? 1 : 0) : AstolfoUi.animate(progress, on ? 1 : 0, 16, surface.delta);
				Paint.toggle(graphics, x + width - 12 - Paint.SWITCH_WIDTH, top + (HEADER_HEIGHT - Paint.SWITCH_HEIGHT) / 2, progress, headerHover);
			}
			if (!rows.isEmpty()) Paint.separator(graphics, x + 12, top + HEADER_HEIGHT, width - 24);
		}
		dim = AstolfoUi.animate(dim, on ? 1 : 0.45f, 14, surface.delta);
		float previous = AstolfoUi.alpha;
		AstolfoUi.alpha = previous * dim;
		Control last = null;
		for (Control row : rows) {
			if (!row.visible) continue;
			if (last != null) Paint.separator(graphics, x + 12, row.y, width - 24);
			row.render(surface);
			last = row;
		}
		AstolfoUi.alpha = previous;
	}

	@Override
	public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) {
		if (!contains(mouseX, mouseY)) return false;
		float top = cardTop();
		if (!title.isEmpty() && mouseY >= top && mouseY < top + HEADER_HEIGHT) {
			if (button == 0 && enabled != null) onToggle.accept(!enabled.getAsBoolean());
			return true;
		}
		for (Control row : rows) if (row.visible && row.mouseClicked(surface, mouseX, mouseY, button)) return true;
		return mouseY >= top;
	}
}
