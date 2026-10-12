package dev.modelmirage.client.visual;

import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import dev.modelmirage.client.Lang;
import dev.modelmirage.client.gui.MirageUi;
import dev.modelmirage.client.gui.kit.ButtonRow;
import dev.modelmirage.client.gui.kit.Settings;
import dev.modelmirage.client.gui.kit.Surface;
import dev.modelmirage.client.gui.kit.Theme;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.slf4j.LoggerFactory;

/** The editor and chat use the same hit boxes, snapping and persistence. */
public final class HudInteraction {
	/** GUI pixels: distance kept from the window edges, gap between neighbours, snap reach and editor grid step. */
	static final float MARGIN = 4, GAP = 4, SNAP = 5, GRID = 8;
	private final boolean editor;
	private final Deque<Map<String, HudLayout.Position>> history = new ArrayDeque<>();
	/** Watermark element under the press; it is reordered once the drag goes sideways. */
	private String pendingChip;
	private boolean reordering;
	private double pressX, pressY;
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

	public HudInteraction(boolean editor) { this.editor = editor; }

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
			pressX = event.x();
			pressY = event.y();
			pendingChip = panel.module().equals("Watermark") ? VisualHud.watermarkChips().stream().filter(chip -> chip.contains(event.x(), event.y()))
					.map(VisualHud.ChipBox::key).findFirst().orElse(null) : null;
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
		if (pendingChip != null && !reordering) {
			// Sideways inside the watermark reorders its elements; pulling it away moves the whole panel.
			if (Math.abs(event.y() - pressY) > panel.screenHeight() * 0.75f) pendingChip = null;
			else if (Math.abs(event.x() - pressX) > 3) { reordering = true; VisualHud.draggedChip = pendingChip; }
			else return true;
		}
		if (reordering) { reorder(event.x()); return true; }
		guideX = guideY = -1;
		float[][] columns = VisualHud.panels().stream().filter(other -> !other.id().equals(selected))
				.map(other -> new float[] {other.screenX(), other.screenWidth()}).toArray(float[][]::new);
		float[][] rows = VisualHud.panels().stream().filter(other -> !other.id().equals(selected))
				.map(other -> new float[] {other.screenY(), other.screenHeight()}).toArray(float[][]::new);
		// Shift places freely; otherwise edges, centres and neighbours attract, and the editor grid catches the rest.
		boolean free = event.hasShiftDown();
		float[] x = free ? new float[] {(float) event.x() - offsetX, -1} : snapAxis((float) event.x() - offsetX, panel.screenWidth(), width, columns, editor);
		float[] y = free ? new float[] {(float) event.y() - offsetY, -1} : snapAxis((float) event.y() - offsetY, panel.screenHeight(), height, rows, editor);
		guideX = x[1];
		guideY = y[1];
		move(panel, x[0], y[0], width, height);
		return true;
	}

	/**
	 * Snaps the start of a panel of {@code size} on one axis to the window margins and centre or to another panel's
	 * start, end, centre or the spot next to it. Returns {start, guide line or -1}; without a match the grid applies.
	 */
	static float[] snapAxis(float start, float size, float extent, float[][] others, boolean grid) {
		List<float[]> candidates = new ArrayList<>();
		candidates.add(new float[] {MARGIN, MARGIN});
		candidates.add(new float[] {(extent - size) / 2, extent / 2});
		candidates.add(new float[] {extent - size - MARGIN, extent - MARGIN});
		for (float[] other : others) {
			float otherStart = other[0], otherEnd = other[0] + other[1];
			candidates.add(new float[] {otherStart, otherStart});
			candidates.add(new float[] {otherEnd - size, otherEnd});
			candidates.add(new float[] {otherStart + other[1] / 2 - size / 2, otherStart + other[1] / 2});
			candidates.add(new float[] {otherEnd + GAP, otherEnd + GAP / 2});
			candidates.add(new float[] {otherStart - GAP - size, otherStart - GAP / 2});
		}
		float[] best = null;
		for (float[] candidate : candidates) {
			if (Math.abs(candidate[0] - start) <= SNAP && (best == null || Math.abs(candidate[0] - start) < Math.abs(best[0] - start))) best = candidate;
		}
		if (best != null) return new float[] {best[0], best[1]};
		return new float[] {grid ? Math.round(start / GRID) * GRID : start, -1};
	}

	/** Moves the dragged watermark element to where the pointer is among the visible ones; hidden ones keep their slots. */
	private void reorder(double pointerX) {
		VisualModule module = Visuals.module("Watermark");
		List<String> visible = new ArrayList<>(VisualHud.watermarkChips().stream().map(VisualHud.ChipBox::key).toList());
		if (!visible.remove(pendingChip)) return;
		int index = (int) VisualHud.watermarkChips().stream().filter(chip -> !chip.key().equals(pendingChip) && chip.center() < pointerX).count();
		visible.add(index, pendingChip);
		List<String> order = new ArrayList<>(module.order("order"));
		Iterator<String> next = visible.iterator();
		for (int i = 0; i < order.size(); i++) if (visible.contains(order.get(i))) order.set(i, next.next());
		String value = String.join(",", order);
		if (value.equals(String.join(",", module.order("order")))) return;
		module.setting("order").set(new JsonPrimitive(value));
		settingsChanged();
	}

	private void move(VisualHud.Panel panel, float x, float y, int width, int height) {
		place(panel.id(), x, y, panel.screenWidth(), panel.screenHeight(), width, height);
	}

	private void place(String id, float x, float y, float panelWidth, float panelHeight, int width, int height) {
		// Same mapping as VisualHud.locate: shares of the space between the margins.
		float freeWidth = width - panelWidth - 2 * MARGIN, freeHeight = height - panelHeight - 2 * MARGIN;
		// In chat, dropping a panel onto the input line means "bottom of the screen"; it is lifted only while chat is open.
		if (!editor && y + panelHeight > height - VisualHud.CHAT_INPUT - 0.5f) y = height - panelHeight - MARGIN;
		HudLayout.place(id, freeWidth > 0 ? Math.clamp(x - MARGIN, 0, freeWidth) / freeWidth : 0, freeHeight > 0 ? Math.clamp(y - MARGIN, 0, freeHeight) / freeHeight : 0);
		dirty = true;
	}

	public boolean release(MouseButtonEvent event) {
		if (popover != null) { pointer(event.x(), event.y()); return surface.mouseReleased(local(event.x()), local(event.y()), event.button()); }
		if (!dragging || event.button() != 0) return false;
		dragging = false;
		guideX = guideY = -1;
		pendingChip = null;
		reordering = false;
		VisualHud.draggedChip = null;
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
			float zoom = Math.clamp(panel.zoom() + (float) amount * 0.05f, 0.5f, 2);
			float ratio = zoom / panel.zoom();
			// Keep the top-left corner where it is while the size changes.
			move(panel, panel.screenX(), panel.screenY(), width, height);
			HudLayout.scale(selected, zoom);
			place(selected, panel.screenX(), panel.screenY(), panel.screenWidth() * ratio, panel.screenHeight() * ratio, width, height);
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
		remember(); move(panel, panel.screenX() + dx, panel.screenY() + dy, width, height); save(); return true;
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

	void settingsChanged() {
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
		if (editor) drawGrid(graphics);
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
	/** Faint lines every two grid steps, brighter while a panel is dragged, and the window centre lines. */
	private void drawGrid(GuiGraphicsExtractor graphics) {
		int width = graphics.guiWidth(), height = graphics.guiHeight();
		int line = dragging && !reordering ? 0x1CFFFFFF : 0x0CFFFFFF;
		int step = Math.round(GRID * 2);
		for (int x = step; x < width; x += step) graphics.fill(x, 0, x + 1, height, line);
		for (int y = step; y < height; y += step) graphics.fill(0, y, width, y + 1, line);
		int centre = MirageUi.opacity(MirageUi.ACCENT, 0.22f);
		graphics.fill(width / 2, 0, width / 2 + 1, height, centre);
		graphics.fill(0, height / 2, width, height / 2 + 1, centre);
	}

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
