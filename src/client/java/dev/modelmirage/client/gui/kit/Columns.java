package dev.modelmirage.client.gui.kit;

import java.util.ArrayList;
import java.util.List;

/** Masonry layout: each child goes to the currently shortest column. */
public final class Columns extends Control {
	private static final int COUNT = 2;
	private static final float GAP = 10;
	public final List<Control> children = new ArrayList<>();

	@Override
	public float layout(float x, float y, float width) {
		this.x = x;
		this.y = y;
		this.width = width;
		float columnWidth = (width - GAP * (COUNT - 1)) / COUNT;
		float[] cursor = new float[COUNT];
		for (Control child : children) {
			int column = 0;
			for (int i = 1; i < COUNT; i++) if (cursor[i] < cursor[column] - 0.5f) column = i;
			float h = child.layout(x + column * (columnWidth + GAP), y + cursor[column], columnWidth);
			if (h > 0) cursor[column] += h + GAP;
		}
		float tallest = 0;
		for (float value : cursor) tallest = Math.max(tallest, value);
		return height = Math.max(0, tallest - GAP);
	}

	@Override
	public void render(Surface surface) { for (Control child : children) if (child.visible) child.render(surface); }

	@Override
	public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) {
		for (Control child : children) if (child.visible && child.mouseClicked(surface, mouseX, mouseY, button)) return true;
		return false;
	}

	@Override
	public boolean mouseScrolled(Surface surface, double mouseX, double mouseY, double amount) {
		for (Control child : children) if (child.visible && child.contains(mouseX, mouseY) && child.mouseScrolled(surface, mouseX, mouseY, amount)) return true;
		return false;
	}
}
