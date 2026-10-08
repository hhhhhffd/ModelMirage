package dev.modelmirage.client.gui.kit;

/** A row whose control is a compact button, e.g. "Редактор HUD → Открыть". */
public final class ButtonRow extends Row {
	private final Button button;

	public ButtonRow(String label, String action, Runnable onPress) {
		super(label);
		button = new Button(action, Button.Style.SECONDARY, onPress);
		button.height = 16;
		button.textSize = Theme.CAPTION + 0.5f;
	}

	@Override
	protected float labelWidth() { return width - button.preferredWidth() - 30; }

	@Override
	protected void renderControl(Surface surface) {
		float w = button.preferredWidth();
		button.layout(right() - w, centerY() - 8, w);
		button.render(surface);
	}

	@Override
	public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) {
		if (!contains(mouseX, mouseY)) return false;
		return this.button.mouseClicked(surface, mouseX, mouseY, button) || true;
	}
}
