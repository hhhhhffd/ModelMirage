package dev.modelmirage.client.gui.kit;

import com.mojang.blaze3d.platform.InputConstants;
import dev.modelmirage.client.gui.AstolfoUi;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/** Rounded search field with an icon, placeholder, caret and clear button. Text is appended at the end. */
public final class SearchField extends Control {
	private final String placeholder;
	private final Consumer<String> changed;
	private String text = "";
	private float focus;

	public SearchField(String placeholder, Consumer<String> changed) {
		this.placeholder = placeholder;
		this.changed = changed;
		height = 22;
	}

	public String text() { return text; }

	public void clear() { set(""); }

	private void set(String value) {
		if (value.equals(text)) return;
		text = value;
		changed.accept(text);
	}

	@Override
	public void render(Surface surface) {
		var graphics = surface.graphics;
		updateHover(surface);
		boolean focused = surface.isFocused(this);
		focus = AstolfoUi.animate(focus, focused ? 1 : 0, 18, surface.delta);
		int fill = AstolfoUi.mix(AstolfoUi.mix(Theme.CONTROL, Theme.CONTROL_HOVER, hover), 0x29FFFFFF, focus);
		AstolfoUi.shape(graphics, x, y, width, height, height / 2, 0, fill, fill);
		if (focus > 0.01f) AstolfoUi.shape(graphics, x - 1.5f, y - 1.5f, width + 3, height + 3, height / 2 + 1.5f, 1.5f, AstolfoUi.opacity(Theme.accent(), focus * 0.6f), AstolfoUi.opacity(Theme.accent(), focus * 0.6f));
		AstolfoUi.ICONS.draw(graphics, "I", x + 8, y + (height - 9) / 2, 9, Theme.TEXT_SECONDARY);
		float textX = x + 22, textY = Paint.centeredTextY(y, height, Theme.BODY);
		float room = width - 22 - (text.isEmpty() ? 10 : 24);
		if (text.isEmpty()) AstolfoUi.MEDIUM.draw(graphics, AstolfoUi.MEDIUM.fit(placeholder, Theme.BODY, room), textX, textY, Theme.BODY, Theme.TEXT_TERTIARY);
		else {
			String shown = text;
			while (!shown.isEmpty() && AstolfoUi.MEDIUM.width(shown, Theme.BODY) > room) shown = shown.substring(1);
			AstolfoUi.MEDIUM.draw(graphics, shown, textX, textY, Theme.BODY, Theme.TEXT);
			textX += AstolfoUi.MEDIUM.width(shown, Theme.BODY);
			float clearX = x + width - 15, cy = y + height / 2;
			AstolfoUi.shape(graphics, clearX - 5, cy - 5, 10, 10, 5, 0, 0x4DEBEBF5, 0x4DEBEBF5);
			graphics.pose().pushMatrix();
			graphics.pose().translate(clearX, cy);
			graphics.pose().rotate((float) Math.toRadians(45));
			AstolfoUi.shape(graphics, -2.6f, -0.5f, 5.2f, 1, 0.5f, 0, 0xFF1C1C1E, 0xFF1C1C1E);
			AstolfoUi.shape(graphics, -0.5f, -2.6f, 1, 5.2f, 0.5f, 0, 0xFF1C1C1E, 0xFF1C1C1E);
			graphics.pose().popMatrix();
		}
		if (focused && System.currentTimeMillis() / 530 % 2 == 0) AstolfoUi.shape(graphics, textX + 0.5f, y + 6, 1, height - 12, 0.5f, 0, Theme.accent(), Theme.accent());
	}

	@Override
	public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) {
		if (!contains(mouseX, mouseY)) return false;
		if (!text.isEmpty() && mouseX >= x + width - 22) set("");
		surface.focus(this);
		return true;
	}

	@Override
	public boolean keyPressed(Surface surface, KeyEvent event) {
		if (event.isEscape()) {
			if (text.isEmpty()) surface.focus(null);
			else set("");
			return true;
		}
		if (event.key() == InputConstants.KEY_BACKSPACE) {
			if (event.hasControlDown()) set(text.replaceAll("\\S*\\s*$", ""));
			else if (!text.isEmpty()) set(text.substring(0, text.offsetByCodePoints(text.length(), -1)));
			return true;
		}
		if (event.isPaste()) {
			String pasted = (text + Minecraft.getInstance().keyboardHandler.getClipboard()).replaceAll("[\\r\\n\\t]", " ");
			set(pasted.substring(0, Math.min(128, pasted.length())));
			return true;
		}
		if (event.isSelectAll()) return true;
		if (event.isConfirmation()) { surface.focus(null); return true; }
		// Swallow printable keys so they are not treated as shortcuts while typing.
		return event.key() >= 32 && event.key() < 256 && !event.hasControlDown();
	}

	@Override
	public boolean charTyped(Surface surface, CharacterEvent event) {
		if (!event.isAllowedChatCharacter() || text.length() >= 128) return false;
		set(text + event.codepointAsString());
		return true;
	}
}
