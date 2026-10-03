package dev.modelmirage.client.visual;

import dev.modelmirage.client.gui.AstolfoUi;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class HudEditorScreen extends Screen {
	private final Screen parent;
	private final HudInteraction interaction = new HudInteraction();
	public HudEditorScreen(Screen parent) { super(Component.literal("Редактор HUD")); this.parent = parent; }

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) { }

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, 0x33000000);
		VisualHud.render(graphics, true);
		interaction.draw(graphics, mouseX, mouseY, true);
		float hintScale = 2f / minecraft.getWindow().getGuiScale();
		float hintWidth = width / hintScale;
		graphics.pose().pushMatrix();
		graphics.pose().scale(hintScale, hintScale);
		String label = "Тяни / колесо: масштаб / стрелки: 1 px / Shift: 10 px / ПКМ: сброс / Ctrl+Z: отмена / Esc: готово";
		float size = Math.min(8, (hintWidth - 20) / AstolfoUi.MEDIUM.width(label, 1));
		float textWidth = AstolfoUi.MEDIUM.width(label, size);
		AstolfoUi.shape(graphics, (hintWidth - textWidth) / 2 - 7, 22, textWidth + 14, 18, 5, 0, 0xE6000000, 0xE6000000);
		AstolfoUi.MEDIUM.draw(graphics, label, (hintWidth - textWidth) / 2, 28, size, AstolfoUi.TEXT);
		graphics.pose().popMatrix();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) { return interaction.click(event, doubleClick, width, height); }
	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) { return interaction.drag(event, width, height); }
	@Override
	public boolean mouseReleased(MouseButtonEvent event) { return interaction.release(event); }
	@Override
	public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { return interaction.scroll(x, y, vertical, width, height); }
	@Override
	public boolean keyPressed(KeyEvent event) { return interaction.key(event, width, height) || super.keyPressed(event); }
	@Override
	public void onClose() { if (interaction.save()) minecraft.gui.setScreen(parent); }
	@Override
	public void removed() { interaction.save(); }
	@Override
	public boolean isPauseScreen() { return false; }
}
