package dev.modelmirage.client.gui.kit;

import dev.modelmirage.client.gui.MirageUi;
import java.util.function.BooleanSupplier;

/** Pill button. Primary uses the accent, secondary a translucent fill, plain only text. */
public final class Button extends Control {
	public enum Style { PRIMARY, SECONDARY, PLAIN, DANGER }

	public String text;
	public String icon = "";
	public Style style;
	public float textSize = Theme.BODY;
	public BooleanSupplier enabled = () -> true;
	private final Runnable onPress;
	private float press;

	public Button(String text, Style style, Runnable onPress) {
		this.text = text;
		this.style = style;
		this.onPress = onPress;
		height = 26;
	}

	public float preferredWidth() {
		float iconWidth = icon.isEmpty() ? 0 : textSize + (text.isEmpty() ? 0 : 5);
		return MirageUi.SEMIBOLD.width(text, textSize) + iconWidth + (text.isEmpty() ? height - textSize : 20);
	}

	@Override
	public void render(Surface surface) {
		if (!visible) return;
		var graphics = surface.graphics;
		updateHover(surface);
		press = MirageUi.animate(press, 0, 14, surface.delta);
		boolean active = enabled.getAsBoolean();
		int fill = switch (style) {
			case PRIMARY -> active ? MirageUi.mix(Theme.accent(), 0xFFFFFFFF, hover * 0.12f) : 0x33787880;
			case DANGER -> MirageUi.opacity(Theme.DANGER, active ? 0.18f + hover * 0.1f : 0.08f);
			case SECONDARY -> MirageUi.mix(Theme.CONTROL, Theme.CONTROL_HOVER, active ? hover : 0);
			case PLAIN -> MirageUi.opacity(Theme.HOVER, active ? hover : 0);
		};
		graphics.pose().pushMatrix();
		float scale = 1 - press * 0.04f;
		graphics.pose().translate(x + width / 2, y + height / 2);
		graphics.pose().scale(scale, scale);
		graphics.pose().translate(-x - width / 2, -y - height / 2);
		MirageUi.shape(graphics, x, y, width, height, Math.min(height / 2, 8), 0, fill, fill);
		int color = !active ? Theme.TEXT_TERTIARY : switch (style) {
			case PRIMARY -> MirageUi.luminance(Theme.accent()) > 0.6f ? 0xFF0B0B0E : 0xFFFFFFFF;
			case DANGER -> Theme.DANGER;
			default -> Theme.TEXT;
		};
		float iconWidth = icon.isEmpty() ? 0 : textSize + (text.isEmpty() ? 0 : 5);
		float content = MirageUi.SEMIBOLD.width(text, textSize) + iconWidth;
		float left = x + (width - content) / 2;
		if (!icon.isEmpty()) MirageUi.ICONS.draw(graphics, icon, left, y + (height - textSize) / 2 - 0.5f, textSize, color);
		MirageUi.SEMIBOLD.draw(graphics, MirageUi.SEMIBOLD.fit(text, textSize, width - 12), left + iconWidth, y + (height - textSize) / 2 - 0.3f, textSize, color);
		graphics.pose().popMatrix();
	}

	@Override
	public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) {
		if (button != 0 || !contains(mouseX, mouseY)) return false;
		if (enabled.getAsBoolean()) { press = 1; onPress.run(); }
		return true;
	}
}
