package dev.modelmirage.client.visual;

import dev.modelmirage.client.gui.kit.Group;
import dev.modelmirage.client.gui.kit.Popup;
import dev.modelmirage.client.gui.kit.Surface;

/** Settings card of one HUD panel, floating next to it inside the popover's local coordinate space. */
final class HudPopover extends Popup {
	private static final float WIDTH = 250, PADDING = 6, GAP = 8, MARGIN = 6;
	private final Group group;
	private VisualHud.Panel panel;

	HudPopover(Group group, VisualHud.Panel panel) {
		this.group = group;
		this.panel = panel;
	}

	/** Height in local units at the current width, used to fit the popover into small windows. */
	float preferredHeight() { return group.layout(0, 0, WIDTH - PADDING * 2) + PADDING * 2; }

	static float preferredWidth() { return WIDTH; }

	/** Right of the panel, else left, else below or above it; always inside the view. */
	void place(Surface surface, float scale) {
		width = WIDTH;
		height = preferredHeight();
		// Follow the panel when it moves or is reset while the popover is open.
		for (var current : VisualHud.panels()) if (current.id().equals(panel.id())) panel = current;
		float left = panel.screenX() / scale, top = panel.screenY() / scale;
		float right = left + panel.screenWidth() / scale, bottom = top + panel.screenHeight() / scale;
		if (right + GAP + WIDTH <= surface.viewWidth - MARGIN) { x = right + GAP; y = top; }
		else if (left - GAP - WIDTH >= MARGIN) { x = left - GAP - WIDTH; y = top; }
		else {
			x = (left + right) / 2 - WIDTH / 2;
			y = bottom + GAP + height <= surface.viewHeight - MARGIN ? bottom + GAP : top - GAP - height;
		}
		x = Math.clamp(x, MARGIN, Math.max(MARGIN, surface.viewWidth - WIDTH - MARGIN));
		y = Math.clamp(y, MARGIN, Math.max(MARGIN, surface.viewHeight - height - MARGIN));
		group.layout(x + PADDING, y + PADDING, WIDTH - PADDING * 2);
	}

	@Override
	protected void renderContent(Surface surface) { group.render(surface); }

	@Override
	public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) { return group.mouseClicked(surface, mouseX, mouseY, button); }
}
