package dev.modelmirage.client.visual;

import com.mojang.blaze3d.platform.InputConstants;
import dev.modelmirage.client.gui.AstolfoUi;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.slf4j.LoggerFactory;

/** The editor and chat use the same hit boxes, snapping and persistence. */
public final class HudInteraction {
	private final Deque<Map<String, HudLayout.Position>> history = new ArrayDeque<>();
	private String selected;
	private boolean dragging;
	private boolean dirty;
	private float offsetX;
	private float offsetY;
	private float guideX = -1;
	private float guideY = -1;
	private String error = "";

	public boolean click(MouseButtonEvent event, boolean doubleClick, int width, int height) {
		var panels = VisualHud.panels();
		for (int i = panels.size() - 1; i >= 0; i--) {
			var panel = panels.get(i);
			if (!panel.contains(event.x(), event.y())) continue;
			selected = panel.id();
			if (event.button() == 1) {
				remember(); HudLayout.reset(selected); dirty = true; save(); return true;
			}
			if (event.button() != 0) return false;
			remember();
			if (doubleClick) { move(panel, (width - panel.screenWidth()) / 2, panel.screenY(), width, height); save(); return true; }
			dragging = true;
			offsetX = (float) event.x() - panel.screenX();
			offsetY = (float) event.y() - panel.screenY();
			return true;
		}
		selected = null;
		return false;
	}

	public boolean drag(MouseButtonEvent event, int width, int height) {
		if (!dragging || event.button() != 0) return false;
		var panel = selectedPanel();
		if (panel == null) { dragging = false; return false; }
		float x = (float) event.x() - offsetX;
		float y = (float) event.y() - offsetY;
		guideX = guideY = -1;
		float[] horizontal = {8, width / 2f - panel.screenWidth() / 2, width - panel.screenWidth() - 8};
		float[] vertical = {8, height / 2f - panel.screenHeight() / 2, height - panel.screenHeight() - 8};
		for (float snap : horizontal) if (Math.abs(x - snap) <= 4) { x = snap; guideX = snap == horizontal[1] ? width / 2f : snap == horizontal[2] ? width - 8 : 8; break; }
		for (float snap : vertical) if (Math.abs(y - snap) <= 4) { y = snap; guideY = snap == vertical[1] ? height / 2f : snap == vertical[2] ? height - 8 : 8; break; }
		for (var other : VisualHud.panels()) {
			if (other.id().equals(selected)) continue;
			if (guideX < 0 && Math.abs(x - other.screenX()) <= 4) { x = other.screenX(); guideX = x; }
			if (guideY < 0 && Math.abs(y - other.screenY()) <= 4) { y = other.screenY(); guideY = y; }
		}
		move(panel, x, y, width, height);
		return true;
	}

	private void move(VisualHud.Panel panel, float x, float y, int width, int height) {
		HudLayout.move(panel.id(), Math.clamp(x, 0, Math.max(0, width - panel.screenWidth())), Math.clamp(y, 0, Math.max(0, height - panel.screenHeight())), width, height);
		dirty = true;
	}

	public boolean release(MouseButtonEvent event) {
		if (!dragging || event.button() != 0) return false;
		dragging = false;
		guideX = guideY = -1;
		save();
		return true;
	}

	public boolean scroll(double x, double y, double amount, int width, int height) {
		var panels = VisualHud.panels();
		for (int i = panels.size() - 1; i >= 0; i--) {
			var panel = panels.get(i);
			if (!panel.contains(x, y)) continue;
			selected = panel.id();
			remember();
			HudLayout.Position position = HudLayout.position(selected, panel.screenX(), panel.screenY(), width, height);
			float zoom = Math.clamp(position.scale() + (float) amount * 0.05f, 0.5f, 2);
			float ratio = zoom / panel.zoom();
			move(panel, Math.clamp(position.x() * width, 0, Math.max(0, width - panel.screenWidth() * ratio)), Math.clamp(position.y() * height, 0, Math.max(0, height - panel.screenHeight() * ratio)), width, height);
			HudLayout.scale(selected, zoom);
			save();
			return true;
		}
		return false;
	}

	public boolean key(KeyEvent event, int width, int height) {
		if (event.hasControlDown() && event.key() == InputConstants.KEY_Z && !history.isEmpty()) {
			HudLayout.restore(history.removeLast()); dirty = true; save(); return true;
		}
		var panel = selectedPanel();
		if (panel == null) return false;
		if (event.key() == InputConstants.KEY_BACKSPACE) { remember(); HudLayout.reset(selected); dirty = true; save(); return true; }
		float step = (float) ((event.hasShiftDown() ? 10f : 1f) / net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale());
		float dx = event.key() == InputConstants.KEY_LEFT ? -step : event.key() == InputConstants.KEY_RIGHT ? step : 0;
		float dy = event.key() == InputConstants.KEY_UP ? -step : event.key() == InputConstants.KEY_DOWN ? step : 0;
		if (dx == 0 && dy == 0) return false;
		HudLayout.Position position = HudLayout.position(selected, panel.screenX(), panel.screenY(), width, height);
		remember(); move(panel, position.x() * width + dx, position.y() * height + dy, width, height); save(); return true;
	}

	private VisualHud.Panel selectedPanel() { return VisualHud.panels().stream().filter(panel -> panel.id().equals(selected)).findFirst().orElse(null); }
	private void remember() { if (history.size() == 32) history.removeFirst(); history.addLast(HudLayout.snapshot()); }

	public boolean save() {
		if (!dirty) return true;
		try { HudLayout.save(); error = ""; dirty = false; return true; }
		catch (IOException failure) { error = "Не удалось сохранить HUD. Esc — повторить"; LoggerFactory.getLogger("ModelMirage").error("Could not save HUD layout", failure); return false; }
	}

	public void draw(GuiGraphicsExtractor graphics, double mouseX, double mouseY, boolean editor) {
		float textScale = 2f / net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale();
		for (var panel : VisualHud.panels()) {
			boolean active = panel.id().equals(selected);
			if (!editor && !active && !panel.contains(mouseX, mouseY)) continue;
			int x = Math.round(panel.screenX());
			int y = Math.round(panel.screenY());
			AstolfoUi.border(graphics, x - 2, y - 2, Math.round(panel.screenWidth()) + 4, Math.round(panel.screenHeight()) + 4, 6, AstolfoUi.opacity(AstolfoUi.ACCENT, active ? 0.9f : 0.35f));
			if (active || panel.contains(mouseX, mouseY)) {
				String label = panel.label();
				float w = AstolfoUi.MEDIUM.width(label, 8 * textScale);
				float labelY = y > 14 * textScale ? y - 13 * textScale : y + panel.screenHeight() + 5 * textScale;
				AstolfoUi.shape(graphics, x, labelY - 2 * textScale, w + 8 * textScale, 12 * textScale, 3 * textScale, 0, 0xDF000000, 0xDF000000);
				AstolfoUi.MEDIUM.draw(graphics, label, x + 4 * textScale, labelY, 8 * textScale, AstolfoUi.TEXT);
			}
		}
		if (guideX >= 0) graphics.fill(Math.round(guideX), 0, Math.round(guideX) + 1, graphics.guiHeight(), 0x6680D6ED);
		if (guideY >= 0) graphics.fill(0, Math.round(guideY), graphics.guiWidth(), Math.round(guideY) + 1, 0x6680D6ED);
		if (!error.isEmpty()) AstolfoUi.MEDIUM.draw(graphics, error, 10, 10, 10, 0xFFFF8080);
	}
}
