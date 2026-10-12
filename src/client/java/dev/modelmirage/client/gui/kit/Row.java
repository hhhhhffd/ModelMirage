package dev.modelmirage.client.gui.kit;

import dev.modelmirage.client.gui.MirageUi;

/** A settings line: label on the left, a control on the right, a soft highlight on hover. */
public abstract class Row extends Control {
	public final String label;

	protected Row(String label) {
		this.label = label;
		this.height = Theme.ROW_HEIGHT;
	}

	@Override
	public void render(Surface surface) {
		updateHover(surface);
		if (hover > 0.01f) {
			int fill = MirageUi.opacity(Theme.HOVER, hover);
			MirageUi.shape(surface.graphics, x + 4, y + 1, width - 8, height - 2, 6, 0, fill, fill);
		}
		float labelWidth = labelWidth();
		MirageUi.MEDIUM.draw(surface.graphics, MirageUi.MEDIUM.fit(label, Theme.BODY, labelWidth), x + 12, Paint.centeredTextY(y, height, Theme.BODY), Theme.BODY, Theme.TEXT);
		renderControl(surface);
	}

	/** Space reserved for the label; controls use the rest of the row. */
	protected float labelWidth() { return width * 0.5f - 14; }

	protected abstract void renderControl(Surface surface);

	protected float centerY() { return y + height / 2f; }
	protected float right() { return x + width - 12; }
}
