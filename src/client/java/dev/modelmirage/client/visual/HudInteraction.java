package dev.modelmirage.client.visual;

import com.mojang.blaze3d.platform.InputConstants;
import dev.modelmirage.client.Lang;
import dev.modelmirage.client.gui.MirageUi;
import dev.modelmirage.client.gui.kit.ButtonRow;
import dev.modelmirage.client.gui.kit.Settings;
import dev.modelmirage.client.gui.kit.Surface;
import dev.modelmirage.client.gui.kit.Theme;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
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
	private boolean settingsDirty;
	/** Debounce deadline of the settings write in nanoTime units; 0 when nothing is scheduled. */
	private long settingsDeadline;
	private String error = "";
	private Surface surface = new Surface();
	private HudPopover popover;

	public boolean click(MouseButtonEvent event, boolean doubleClick, int width, int height) {
		if (popover != null) {
			pointer(event.x(), event.y());
			// A dropdown inside the popover is the surface's popup: it takes the click (and closes on an outside one).
			if (surface.hasPopup() || popover.contains(local(event.x()), local(event.y()))) {
				surface.mouseClicked(List.of(popover), local(event.x()), local(event.y()), event.button());
				return true;
			}
			closePopover();
			if (event.button() != 1) return true;
		}
		var panels = VisualHud.panels();
		for (int i = panels.size() - 1; i >= 0; i--) {
			var panel = panels.get(i);
			if (!panel.contains(event.x(), event.y())) continue;
			selected = panel.id();
			if (event.button() == 1) { openPopover(panel); return true; }
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
		if (popover != null) { pointer(event.x(), event.y()); return surface.mouseDragged(local(event.x()), local(event.y()), event.button()); }
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
		if (popover != null) { pointer(event.x(), event.y()); return surface.mouseReleased(local(event.x()), local(event.y()), event.button()); }
		if (!dragging || event.button() != 0) return false;
		dragging = false;
		guideX = guideY = -1;
		save();
		return true;
	}

	public boolean scroll(double x, double y, double amount, int width, int height) {
		if (popover != null) { pointer(x, y); surface.mouseScrolled(List.of(popover), local(x), local(y), amount); return true; }
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

	/** While the popover is open it owns the keyboard (key binding, Esc); returns true when the key was taken. */
	public boolean popoverKey(KeyEvent event) {
		if (!popoverOpen()) return false;
		// Esc closes an open dropdown or cancels a key binding first, then the popover itself.
		if (!surface.keyPressed(event) && event.isEscape()) closePopover();
		return true;
	}

	public boolean popoverOpen() { return popover != null; }

	public boolean key(KeyEvent event, int width, int height) {
		if (popoverKey(event)) return true;
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

	private static float scale() { return 2f / (float) Minecraft.getInstance().getWindow().getGuiScale(); }
	/** Popover scale: the menu's physical size, shrunk when the window is too small to fit it. */
	private float popoverScale = 1;
	private float local(double coordinate) { return (float) coordinate / popoverScale; }
	private void pointer(double x, double y) { surface.mouseX = local(x); surface.mouseY = local(y); }

	private void openPopover(VisualHud.Panel panel) {
		closePopover();
		var group = Settings.module(Visuals.module(panel.module()), this::settingsChanged);
		group.add(new ButtonRow(Lang.tr("modelmirage.hud.position"), Lang.tr("modelmirage.action.reset"), () -> { remember(); HudLayout.reset(panel.id()); dirty = true; save(); }));
		popover = new HudPopover(group, panel);
		surface = new Surface();
	}

	private void closePopover() {
		if (popover == null) return;
		surface.close();
		surface.focus(null);
		popover = null;
		save();
	}

	private void settingsChanged() {
		settingsDirty = true;
		settingsDeadline = System.nanoTime() + 500_000_000L;
	}

	/** Flushes pending layout and settings; false when a write failed. */
	public boolean save() {
		String failure = "";
		settingsDeadline = 0;
		if (dirty) {
			try { HudLayout.save(); dirty = false; }
			catch (IOException exception) { failure = Lang.tr("modelmirage.hud.save_layout_failed"); LoggerFactory.getLogger("ModelMirage").error("Could not save HUD layout", exception); }
		}
		if (settingsDirty) {
			try { Visuals.save(); settingsDirty = false; }
			catch (IOException exception) { failure = Lang.tr("modelmirage.hud.save_settings_failed"); LoggerFactory.getLogger("ModelMirage").error("Could not save visual settings", exception); }
		}
		error = failure;
		return failure.isEmpty();
	}

	public void draw(GuiGraphicsExtractor graphics, double mouseX, double mouseY, boolean editor) {
		if (settingsDeadline != 0 && System.nanoTime() - settingsDeadline >= 0) save();
		float scale = scale();
		for (var panel : VisualHud.panels()) {
			boolean active = panel.id().equals(selected);
			boolean hovered = panel.contains(mouseX, mouseY) && popover == null;
			if (!editor && !active && !hovered) continue;
			float x = panel.screenX();
			float y = panel.screenY();
			float radius = 8 * panel.scale() * panel.zoom() + 3;
			int outline = active ? MirageUi.ACCENT : MirageUi.opacity(MirageUi.ACCENT, hovered ? 0.5f : 0.18f);
			MirageUi.shape(graphics, x - 3, y - 3, panel.screenWidth() + 6, panel.screenHeight() + 6, radius, active ? 1.4f : 1, outline, outline);
			if (active || hovered) chip(graphics, panel, scale);
		}
		int guide = MirageUi.opacity(MirageUi.ACCENT, 0.6f);
		if (guideX >= 0) graphics.fill(Math.round(guideX), 0, Math.round(guideX) + 1, graphics.guiHeight(), guide);
		if (guideY >= 0) graphics.fill(0, Math.round(guideY), graphics.guiWidth(), Math.round(guideY) + 1, guide);
		graphics.pose().pushMatrix();
		graphics.pose().scale(scale, scale);
		if (!error.isEmpty()) MirageUi.MEDIUM.draw(graphics, error, 10, 10, 9, Theme.DANGER);
		graphics.pose().popMatrix();
		if (popover != null) {
			popoverScale = Math.min(scale * 0.8f, Math.min((graphics.guiHeight() - 12f) / popover.preferredHeight(), (graphics.guiWidth() - 12f) / HudPopover.preferredWidth()));
			graphics.pose().pushMatrix();
			graphics.pose().scale(popoverScale, popoverScale);
			surface.begin(graphics, mouseX / popoverScale, mouseY / popoverScale);
			surface.viewWidth = graphics.guiWidth() / popoverScale;
			surface.viewHeight = graphics.guiHeight() / popoverScale;
			popover.place(surface, popoverScale);
			popover.render(surface);
			surface.renderPopup();
			graphics.pose().popMatrix();
		}
	}

	/** Glass label with the panel name and zoom, above the panel or below it when there is no room. */
	private void chip(GuiGraphicsExtractor graphics, VisualHud.Panel panel, float scale) {
		String name = panel.label();
		String zoom = Math.round(panel.zoom() * 100) + "%";
		float width = MirageUi.SEMIBOLD.width(name, 6.5f) + MirageUi.MEDIUM.width(zoom, 6) + 20;
		float x = Math.clamp(panel.screenX() / scale, 4, Math.max(4, graphics.guiWidth() / scale - width - 4));
		float top = panel.screenY() / scale - 21;
		float y = top >= 4 ? top : (panel.screenY() + panel.screenHeight()) / scale + 7;
		graphics.pose().pushMatrix();
		graphics.pose().scale(scale, scale);
		MirageUi.glass(graphics, x, y, width, 15, 7.5f, 10, 0x90101014);
		MirageUi.shape(graphics, x, y, width, 15, 7.5f, 0.8f, 0x2AFFFFFF, 0x0CFFFFFF);
		MirageUi.SEMIBOLD.draw(graphics, name, x + 8, y + 4.2f, 6.5f, Theme.TEXT);
		MirageUi.MEDIUM.draw(graphics, zoom, x + 13 + MirageUi.SEMIBOLD.width(name, 6.5f), y + 4.5f, 6, Theme.TEXT_SECONDARY);
		graphics.pose().popMatrix();
	}
}
