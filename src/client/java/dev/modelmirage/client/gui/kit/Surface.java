package dev.modelmirage.client.gui.kit;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/**
 * Per-screen UI state shared by controls: frame timing, mouse position, keyboard focus,
 * pointer capture and a single popup layer drawn above everything else.
 */
public final class Surface {
	public GuiGraphicsExtractor graphics;
	public float mouseX, mouseY, delta;
	/** Visible area in local units; popups keep themselves inside it. */
	public float viewWidth = 10000, viewHeight = 10000;
	private Control focused, captured, popup;
	private int capturedButton, focusChanges;
	/** The last key press ended a focus (e.g. a key binding); its character event must not leak elsewhere. */
	private boolean swallowChar;
	private long frame = System.nanoTime();

	public void begin(GuiGraphicsExtractor graphics, double mouseX, double mouseY) {
		long now = System.nanoTime();
		delta = Math.min((now - frame) / 1e9f, 0.1f);
		frame = now;
		this.graphics = graphics;
		this.mouseX = (float) mouseX;
		this.mouseY = (float) mouseY;
	}

	/** Mouse position seen by controls under an open popup, so they do not light up behind it. */
	public boolean blockedByPopup(double mouseX, double mouseY) { return popup != null && popup.contains(mouseX, mouseY); }

	public void renderPopup() {
		if (popup == null) return;
		popup.render(this);
	}

	public boolean mouseClicked(List<? extends Control> roots, double mouseX, double mouseY, int button) {
		if (popup != null) {
			if (popup.contains(mouseX, mouseY)) popup.mouseClicked(this, mouseX, mouseY, button);
			else close();
			return true;
		}
		int changes = focusChanges;
		for (int i = roots.size() - 1; i >= 0; i--) {
			Control root = roots.get(i);
			if (!root.visible || !root.mouseClicked(this, mouseX, mouseY, button)) continue;
			// Clicking anything that does not take focus itself ends the previous focus (e.g. a pending key binding).
			if (focusChanges == changes) focus(null);
			return true;
		}
		focus(null);
		return false;
	}

	public boolean mouseDragged(double mouseX, double mouseY, int button) {
		return captured != null && button == capturedButton && captured.mouseDragged(this, mouseX, mouseY, button);
	}

	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (captured == null || button != capturedButton) return false;
		Control control = captured;
		captured = null;
		control.mouseReleased(this, mouseX, mouseY, button);
		return true;
	}

	public boolean mouseScrolled(List<? extends Control> roots, double mouseX, double mouseY, double amount) {
		if (popup != null) return popup.contains(mouseX, mouseY) && popup.mouseScrolled(this, mouseX, mouseY, amount) || popup.contains(mouseX, mouseY);
		for (int i = roots.size() - 1; i >= 0; i--) {
			Control root = roots.get(i);
			if (root.visible && root.mouseScrolled(this, mouseX, mouseY, amount)) return true;
		}
		return false;
	}

	public boolean keyPressed(KeyEvent event) {
		swallowChar = false;
		if (focused != null) {
			Control control = focused;
			boolean handled = control.keyPressed(this, event);
			swallowChar = handled && focused == null;
			if (handled) return true;
		}
		if (popup != null && event.isEscape()) { close(); return true; }
		return popup != null && popup.keyPressed(this, event);
	}

	public boolean charTyped(CharacterEvent event) {
		if (swallowChar) { swallowChar = false; return true; }
		return focused != null && focused.charTyped(this, event);
	}

	/** The control receives drags and the release of the current press. */
	public void capture(Control control) { capture(control, 0); }
	public void capture(Control control, int button) { captured = control; capturedButton = button; }

	public void focus(Control control) {
		focusChanges++;
		if (focused == control) return;
		Control previous = focused;
		focused = control;
		if (previous != null) previous.blur(this);
	}

	public boolean isFocused(Control control) { return focused == control; }
	public boolean isCapturing() { return captured != null; }

	public void open(Control popup) { this.popup = popup; }
	public void close() {
		if (popup != null && focused == popup) focus(null);
		popup = null;
	}
	public boolean isOpen(Control popup) { return this.popup == popup; }
	public boolean hasPopup() { return popup != null; }
}
