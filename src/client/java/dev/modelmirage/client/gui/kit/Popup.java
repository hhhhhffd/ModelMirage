package dev.modelmirage.client.gui.kit;

import dev.modelmirage.client.gui.MirageUi;

/** Floating glass card anchored to a control; fades and scales in from its anchor edge. */
public abstract class Popup extends Control {
	protected float opening;

	/** Places the popup below (or above, when there is no room) the given anchor rectangle. */
	protected void anchor(Surface surface, float anchorX, float anchorY, float anchorWidth, float anchorHeight, float popupWidth, float popupHeight) {
		width = popupWidth;
		height = popupHeight;
		x = Math.clamp(anchorX + anchorWidth - popupWidth, 6, Math.max(6, surface.viewWidth - popupWidth - 6));
		boolean below = anchorY + anchorHeight + 4 + popupHeight <= surface.viewHeight - 6 || anchorY - popupHeight - 4 < 6;
		y = below ? anchorY + anchorHeight + 4 : anchorY - popupHeight - 4;
	}

	@Override
	public final void render(Surface surface) {
		opening = MirageUi.animate(opening, 1, 22, surface.delta);
		var graphics = surface.graphics;
		float previous = MirageUi.alpha;
		MirageUi.alpha = previous * opening;
		graphics.pose().pushMatrix();
		float scale = 0.96f + 0.04f * opening;
		graphics.pose().translate(x + width / 2, y);
		graphics.pose().scale(scale, scale);
		graphics.pose().translate(-x - width / 2, -y + (1 - opening) * -4);
		MirageUi.shadow(graphics, x, y, width, height, 9);
		MirageUi.glass(graphics, x, y, width, height, 9, 14, Theme.POPUP_TINT);
		MirageUi.shape(graphics, x, y, width, height, 9, 1, 0x1FFFFFFF, 0x0FFFFFFF);
		renderContent(surface);
		graphics.pose().popMatrix();
		MirageUi.alpha = previous;
	}

	protected abstract void renderContent(Surface surface);

	protected boolean over(Surface surface, float left, float top, float w, float h) {
		return surface.mouseX >= left && surface.mouseX < left + w && surface.mouseY >= top && surface.mouseY < top + h;
	}
}
