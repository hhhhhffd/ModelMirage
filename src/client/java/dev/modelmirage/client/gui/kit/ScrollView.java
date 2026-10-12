package dev.modelmirage.client.gui.kit;

import dev.modelmirage.client.gui.MirageUi;

/** Clipped, smoothly scrolling viewport with an overlay scrollbar that shows while in use. */
public final class ScrollView extends Control {
	private static final float GUTTER = 8;
	public Control content;
	private float scroll, target, maximum, barAlpha;
	private boolean dragging;
	private long lastScroll;

	public ScrollView(Control content) { this.content = content; }

	public void reset() { scroll = target = 0; }

	/** Positions the viewport; content is laid out shifted by the current scroll offset. */
	public void place(float x, float y, float width, float height, float delta) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		scroll = MirageUi.animate(scroll, target, 20, delta);
		// A fixed gutter keeps the scrollbar off the content and the layout stable when scrolling appears.
		float contentHeight = content.layout(x, y - scroll, width - GUTTER);
		maximum = Math.max(0, contentHeight - height);
		if (target > maximum) { target = maximum; scroll = Math.min(scroll, maximum); }
	}

	@Override
	public void render(Surface surface) {
		var graphics = surface.graphics;
		boolean inside = contains(surface.mouseX, surface.mouseY);
		float mouseX = surface.mouseX, mouseY = surface.mouseY;
		if (!inside) { surface.mouseX = -10000; surface.mouseY = -10000; }
		graphics.enableScissor(Math.round(x), Math.round(y), Math.round(x + width), Math.round(y + height));
		content.render(surface);
		graphics.disableScissor();
		surface.mouseX = mouseX;
		surface.mouseY = mouseY;
		boolean active = dragging || System.currentTimeMillis() - lastScroll < 900 || inside && surface.mouseX > x + width - 10;
		barAlpha = MirageUi.animate(barAlpha, maximum > 0 && active ? 1 : maximum > 0 && inside ? 0.35f : 0, 10, surface.delta);
		if (barAlpha > 0.01f) {
			float track = height - 8, thumb = Math.max(24, track * height / (height + maximum));
			float top = y + 4 + (maximum == 0 ? 0 : scroll / maximum * (track - thumb));
			int color = MirageUi.opacity(0x80FFFFFF, barAlpha);
			MirageUi.shape(graphics, x + width - 5, top, 3, thumb, 1.5f, 0, color, color);
		}
	}

	@Override
	public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) {
		if (!contains(mouseX, mouseY)) return false;
		if (button == 0 && maximum > 0 && mouseX >= x + width - 8) {
			dragging = true;
			surface.capture(this);
			drag(mouseY);
			return true;
		}
		return content.mouseClicked(surface, mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(Surface surface, double mouseX, double mouseY, int button) {
		if (!dragging) return false;
		drag(mouseY);
		return true;
	}

	@Override
	public void mouseReleased(Surface surface, double mouseX, double mouseY, int button) { dragging = false; }

	private void drag(double mouseY) {
		float track = height - 8, thumb = Math.max(24, track * height / (height + maximum));
		target = scroll = Math.clamp((float) ((mouseY - y - 4 - thumb / 2) / (track - thumb)) * maximum, 0, maximum);
		lastScroll = System.currentTimeMillis();
	}

	@Override
	public boolean mouseScrolled(Surface surface, double mouseX, double mouseY, double amount) {
		if (!contains(mouseX, mouseY)) return false;
		if (content.mouseScrolled(surface, mouseX, mouseY, amount)) return true;
		if (maximum <= 0) return false;
		target = Math.clamp(target - (float) amount * 40, 0, maximum);
		lastScroll = System.currentTimeMillis();
		return true;
	}
}
