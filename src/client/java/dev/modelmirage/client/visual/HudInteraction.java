package dev.modelmirage.client.visual;

import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import dev.modelmirage.client.Lang;
import dev.modelmirage.client.gui.MirageUi;
import dev.modelmirage.client.gui.kit.ButtonRow;
import dev.modelmirage.client.gui.kit.Group;
import dev.modelmirage.client.gui.kit.Settings;
import dev.modelmirage.client.gui.kit.SliderRow;
import dev.modelmirage.client.gui.kit.Surface;
import dev.modelmirage.client.gui.kit.Theme;
import dev.modelmirage.client.gui.kit.ToggleRow;
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

/** Layout editing while the chat is open: dragging, snapping, element switches, panel settings and persistence. */
public final class HudInteraction {
	/** GUI pixels: distance kept from the window edges, gap between neighbours, snap reach and grid step. */
	static final float MARGIN = 4, GAP = 4, SNAP = 3, GRID = 8;
	/** Fabric does not report double clicks to chat listeners; two presses on one panel this close count as one. */
	private static final long DOUBLE_CLICK_NANOS = 300_000_000L;
	/** Panel zoom per mouse wheel notch. */
	private static final float WHEEL_STEP = 0.1f;
	/** Every HUD element with only its on/off switch; settings stay in the right-click popover. */
	private final Group elements = new Group().title(Lang.tr("modelmirage.hud.editor.elements"));
	/** Module of each row of {@link #elements}, in the same order. */
	private final List<VisualModule> elementModules = new ArrayList<>();
	private final Surface listSurface = new Surface();
	private final String legend = Lang.tr("modelmirage.hud.editor.legend");
	private float listScale = 1;
	private final Deque<Map<String, HudLayout.Position>> history = new ArrayDeque<>();
	/** Watermark element under a Ctrl press; it is reordered once the drag goes sideways. */
	private String pendingChip;
	private String lastClickPanel;
	private long lastClickTime;
	private boolean reordering;
	private double pressX;
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

	public HudInteraction() {
		for (VisualModule module : Visuals.modules()) {
			if (!module.isPanel() && !module.category.equals("mascot") && !module.id.equals("Crosshair")) continue;
			elements.add(new ToggleRow(module.name(), () -> module.enabled, value -> { module.enabled = value; settingsChanged(); }));
			elementModules.add(module);
		}
	}

	public boolean click(MouseButtonEvent event, int width, int height) {
		double listX = event.x() / listScale, listY = event.y() / listScale;
		if (popover == null && elements.contains(listX, listY)) {
			// Right click on a row opens that element's settings; the crosshair has no panel to click on the HUD.
			if (event.button() == 1) for (int i = 0; i < elements.rows.size(); i++) {
				var row = elements.rows.get(i);
				if (row.contains(listX, listY)) { openListPopover(elementModules.get(i), row.x, row.y, row.width, row.height); return true; }
			}
			listSurface.mouseClicked(List.of(elements), listX, listY, event.button());
			return true;
		}
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
			long now = System.nanoTime();
			boolean doubleClick = panel.id().equals(lastClickPanel) && now - lastClickTime < DOUBLE_CLICK_NANOS;
			lastClickPanel = doubleClick ? null : panel.id();
			lastClickTime = now;
			remember();
			if (doubleClick) { move(panel, (width - panel.screenWidth()) / 2, panel.screenY(), width, height); save(); return true; }
			dragging = true;
			offsetX = (float) event.x() - panel.screenX();
			offsetY = (float) event.y() - panel.screenY();
			pressX = event.x();
			// A plain drag always moves the panel; Ctrl picks up one watermark element to reorder it.
			pendingChip = panel.module().equals("Watermark") && event.hasControlDown() ? VisualHud.watermarkChips().stream().filter(chip -> chip.contains(event.x(), event.y()))
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
			if (Math.abs(event.x() - pressX) <= 2) return true;
			reordering = true;
			VisualHud.draggedChip = pendingChip;
		}
		if (reordering) { reorder(event.x()); return true; }
		guideX = guideY = -1;
		float[][] columns = VisualHud.panels().stream().filter(other -> !other.id().equals(selected))
				.map(other -> new float[] {other.screenX(), other.screenWidth()}).toArray(float[][]::new);
		float[][] rows = VisualHud.panels().stream().filter(other -> !other.id().equals(selected))
				.map(other -> new float[] {other.screenY(), other.screenHeight()}).toArray(float[][]::new);
		// Shift places freely; otherwise edges, centres and neighbours attract, and the grid (when shown) catches the rest.
		boolean free = event.hasShiftDown();
		float[] x = free ? new float[] {(float) event.x() - offsetX, -1} : snapAxis((float) event.x() - offsetX, panel.screenWidth(), width, columns, VisualPreferences.hudGrid);
		float[] y = free ? new float[] {(float) event.y() - offsetY, -1} : snapAxis((float) event.y() - offsetY, panel.screenHeight(), height, rows, VisualPreferences.hudGrid);
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
		if (y + panelHeight > height - VisualHud.CHAT_INPUT - 0.5f) y = height - panelHeight - MARGIN;
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
			zoom(panel, panel.zoom() + (float) amount * WHEEL_STEP, width, height);
			save();
			return true;
		}
		return false;
	}

	/** Sets a panel's own scale on top of the global HUD scale, keeping its top-left corner in place. */
	private void zoom(VisualHud.Panel panel, float value, int width, int height) {
		float zoom = Math.clamp(value, 0.5f, 2);
		float ratio = zoom / panel.zoom();
		move(panel, panel.screenX(), panel.screenY(), width, height);
		HudLayout.scale(panel.id(), zoom);
		place(panel.id(), panel.screenX(), panel.screenY(), panel.screenWidth() * ratio, panel.screenHeight() * ratio, width, height);
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

	/** The panel as drawn this frame, or the given one while it is hidden. */
	private static VisualHud.Panel currentPanel(VisualHud.Panel panel) {
		return VisualHud.panels().stream().filter(current -> current.id().equals(panel.id())).findFirst().orElse(panel);
	}

	private VisualHud.Panel selectedPanel() { return VisualHud.panels().stream().filter(panel -> panel.id().equals(selected)).findFirst().orElse(null); }
	private void remember() { if (history.size() == 32) history.removeFirst(); history.addLast(HudLayout.snapshot()); }

	private static float scale() { return 2f / (float) Minecraft.getInstance().getWindow().getGuiScale(); }
	/** Popover scale: the menu's physical size, shrunk when the window is too small to fit it. */
	private float popoverScale = 1;
	private float local(double coordinate) { return (float) coordinate / popoverScale; }
	private void pointer(double x, double y) { surface.mouseX = local(x); surface.mouseY = local(y); }

	/** Settings of a module opened from its list row: beside its panel when it is shown, else beside the row. */
	private void openListPopover(VisualModule module, float x, float y, float width, float height) {
		var shown = VisualHud.panels().stream().filter(panel -> panel.module().equals(module.id)).findFirst();
		if (shown.isPresent()) { selected = shown.get().id(); openPopover(shown.get()); return; }
		var row = new VisualHud.Panel("list:" + module.id, module.id, module.name(), Math.round(x), Math.round(y), Math.round(width), Math.round(height), listScale, 1);
		closePopover();
		popover = new HudPopover(Settings.module(module, this::settingsChanged), row);
		surface = new Surface();
	}

	private void openPopover(VisualHud.Panel panel) {
		closePopover();
		var group = Settings.module(Visuals.module(panel.module()), this::settingsChanged);
		// Reads the live panel: it is rebuilt every frame and the slider must follow wheel zooming too.
		group.add(new SliderRow(Lang.tr("modelmirage.hud.scale"), 50, 200, 5, "%", () -> Math.round(currentPanel(panel).zoom() * 100), value -> {
			var current = currentPanel(panel);
			if (Math.abs(current.zoom() * 100 - value) < 0.5) return;
			Minecraft client = Minecraft.getInstance();
			zoom(current, (float) value / 100, client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight());
			dirty = true;
		}).deferred());
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

	public void draw(GuiGraphicsExtractor graphics, double mouseX, double mouseY) {
		if (settingsDeadline != 0 && System.nanoTime() - settingsDeadline >= 0) save();
		if (VisualPreferences.hudGrid) drawGrid(graphics);
		float scale = scale();
		// Outlines and guides are measured in physical pixels: GUI scale, HUD scale and panel zoom must not thicken them.
		float px = MirageUi.hairline(graphics);
		for (var panel : VisualHud.panels()) {
			boolean active = panel.id().equals(selected);
			boolean hovered = panel.contains(mouseX, mouseY) && popover == null;
			float x = panel.screenX();
			float y = panel.screenY();
			float gap = 3 * px * 2;
			float radius = 8 * panel.scale() * panel.zoom() + gap;
			int outline = active ? MirageUi.ACCENT : MirageUi.opacity(MirageUi.ACCENT, hovered ? 0.5f : 0.18f);
			MirageUi.shape(graphics, x - gap, y - gap, panel.screenWidth() + gap * 2, panel.screenHeight() + gap * 2, radius, (active ? 2 : 1) * px, outline, outline);
			if (active || hovered) chip(graphics, panel, scale);
		}
		int guide = MirageUi.opacity(MirageUi.ACCENT, 0.6f);
		if (guideX >= 0) MirageUi.shape(graphics, guideX, 0, px, graphics.guiHeight(), 0, 0, guide, guide);
		if (guideY >= 0) MirageUi.shape(graphics, 0, guideY, graphics.guiWidth(), px, 0, 0, guide, guide);
		drawElements(graphics, mouseX, mouseY);
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
	/** Lines every two grid steps, brighter while a panel is dragged, plus the window centre lines; all one pixel wide. */
	private void drawGrid(GuiGraphicsExtractor graphics) {
		float width = graphics.guiWidth(), height = graphics.guiHeight(), px = MirageUi.hairline(graphics);
		int line = dragging && !reordering ? 0x1CFFFFFF : 0x0CFFFFFF;
		for (float x = GRID * 2; x < width; x += GRID * 2) MirageUi.shape(graphics, x, 0, px, height, 0, 0, line, line);
		for (float y = GRID * 2; y < height; y += GRID * 2) MirageUi.shape(graphics, 0, y, width, px, 0, 0, line, line);
		int centre = MirageUi.opacity(MirageUi.ACCENT, 0.22f);
		MirageUi.shape(graphics, width / 2, 0, px, height, 0, 0, centre, centre);
		MirageUi.shape(graphics, 0, height / 2, width, px, 0, 0, centre, centre);
	}

	/** Element switches on the right, the controls legend above them; clear of the chat in the bottom-left. */
	private void drawElements(GuiGraphicsExtractor graphics, double mouseX, double mouseY) {
		listScale = 1.6f / (float) Minecraft.getInstance().getWindow().getGuiScale();
		float viewWidth = graphics.guiWidth() / listScale, viewHeight = graphics.guiHeight() / listScale;
		// Legend lines are "key: action"; keys form a bright column, actions a dimmer one.
		float size = 9, lineHeight = 14, padding = 9;
		String[] lines = legend.split(" \\| ");
		String[] keys = new String[lines.length], actions = new String[lines.length];
		float keyWidth = 0, actionWidth = 0;
		for (int i = 0; i < lines.length; i++) {
			int colon = lines[i].indexOf(": ");
			keys[i] = colon < 0 ? "" : lines[i].substring(0, colon);
			actions[i] = colon < 0 ? lines[i] : lines[i].substring(colon + 2);
			keyWidth = Math.max(keyWidth, MirageUi.SEMIBOLD.width(keys[i], size));
			actionWidth = Math.max(actionWidth, MirageUi.MEDIUM.width(actions[i], size));
		}
		float legendWidth = padding * 2 + keyWidth + 10 + actionWidth, legendHeight = padding * 2 + lines.length * lineHeight - 4;
		float listWidth = 150;
		float listHeight = elements.layout(0, 0, listWidth);
		elements.layout(viewWidth - listWidth - 10, Math.max(legendHeight + 18, (viewHeight - listHeight) / 2), listWidth);
		graphics.pose().pushMatrix();
		graphics.pose().scale(listScale, listScale);
		listSurface.begin(graphics, mouseX / listScale, mouseY / listScale);
		MirageUi.shadow(graphics, elements.x, elements.y, elements.width, elements.height, Theme.RADIUS_CARD);
		MirageUi.glass(graphics, elements.x, elements.y, elements.width, elements.height, Theme.RADIUS_CARD, VisualPreferences.blur, Theme.POPUP_TINT);
		elements.render(listSurface);
		float right = elements.x + elements.width, left = right - legendWidth, top = elements.y - 8 - legendHeight;
		// Glass behind the legend keeps it readable over a bright sky.
		MirageUi.shadow(graphics, left, top, legendWidth, legendHeight, Theme.RADIUS_CARD);
		MirageUi.glass(graphics, left, top, legendWidth, legendHeight, Theme.RADIUS_CARD, VisualPreferences.blur, Theme.POPUP_TINT);
		float y = top + padding;
		for (int i = 0; i < lines.length; i++) {
			MirageUi.SEMIBOLD.draw(graphics, keys[i], left + padding, y, size, Theme.TEXT);
			MirageUi.MEDIUM.draw(graphics, actions[i], left + padding + keyWidth + 10, y, size, Theme.TEXT_SECONDARY);
			y += lineHeight;
		}
		graphics.pose().popMatrix();
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
