package dev.modelmirage.client.gui.kit;

import dev.modelmirage.client.gui.MirageUi;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/**
 * A positioned UI element in local (already scaled) coordinates. Containers call {@link #layout}
 * before rendering; events are offered top-down and return true when consumed.
 */
public abstract class Control {
	public float x, y, width, height;
	public boolean visible = true;
	protected float hover;

	/** Places the control and returns its height. */
	public float layout(float x, float y, float width) {
		this.x = x;
		this.y = y;
		this.width = width;
		return height;
	}

	public abstract void render(Surface surface);

	public boolean contains(double mouseX, double mouseY) {
		return visible && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
	}

	/** Eases {@link #hover} towards the hovered state; returns it for convenience. */
	protected float updateHover(Surface surface) {
		boolean over = contains(surface.mouseX, surface.mouseY) && !surface.blockedByPopup(surface.mouseX, surface.mouseY);
		hover = MirageUi.animate(hover, over ? 1 : 0, 18, surface.delta);
		return hover;
	}

	public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) { return false; }
	public boolean mouseDragged(Surface surface, double mouseX, double mouseY, int button) { return false; }
	public void mouseReleased(Surface surface, double mouseX, double mouseY, int button) { }
	public boolean mouseScrolled(Surface surface, double mouseX, double mouseY, double amount) { return false; }
	/** Only delivered to the focused control. */
	public boolean keyPressed(Surface surface, KeyEvent event) { return false; }
	/** Only delivered to the focused control. */
	public boolean charTyped(Surface surface, CharacterEvent event) { return false; }
}
