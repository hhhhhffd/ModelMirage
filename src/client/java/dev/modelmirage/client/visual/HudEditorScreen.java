package dev.modelmirage.client.visual;

import dev.modelmirage.client.gui.AstolfoUi;
import java.io.IOException;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.LoggerFactory;

public final class HudEditorScreen extends Screen {
	private final Screen parent;
	private VisualHud.Panel dragging;
	private float offsetX;
	private float offsetY;
	private String error = "";
	public HudEditorScreen(Screen parent) { super(Component.literal("Редактор HUD")); this.parent = parent; }

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, 0x4403070D);
		for (int x = 0; x < width; x += 12) graphics.fill(x, 0, x + 1, height, 0x0B94B9DC);
		for (int y = 0; y < height; y += 12) graphics.fill(0, y, width, y + 1, 0x0B94B9DC);
		VisualHud.render(graphics, true);
		String label = error.isEmpty() ? "Перетаскивай панели. Esc: сохранить и вернуться" : error;
		float textWidth = AstolfoUi.MEDIUM.width(label, 10);
		AstolfoUi.MEDIUM.draw(graphics, label, (width - textWidth) / 2, height - 14, 10, AstolfoUi.TEXT);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) return false;
		var panels = VisualHud.panels();
		for (int index = panels.size() - 1; index >= 0; index--) {
			var panel = panels.get(index);
			if (panel.contains(event.x(), event.y())) {
				dragging = panel;
				offsetX = (float) event.x() - panel.screenX();
				offsetY = (float) event.y() - panel.screenY();
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging == null) return false;
		float x = Math.clamp((float) event.x() - offsetX, 0, Math.max(0, width - dragging.screenWidth()));
		float y = Math.clamp((float) event.y() - offsetY, 0, Math.max(0, height - dragging.screenHeight()));
		HudLayout.move(dragging.id(), x, y, width, height);
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) { dragging = null; return true; }

	@Override
	public void onClose() {
		try { HudLayout.save(); minecraft.gui.setScreen(parent); }
		catch (IOException failure) { error = "Не удалось сохранить расположение"; LoggerFactory.getLogger("ModelMirage").error("Could not save HUD layout", failure); }
	}

	@Override
	public boolean isPauseScreen() { return false; }
}
