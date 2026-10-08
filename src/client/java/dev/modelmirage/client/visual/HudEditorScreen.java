package dev.modelmirage.client.visual;

import dev.modelmirage.client.gui.AstolfoUi;
import dev.modelmirage.client.gui.kit.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class HudEditorScreen extends Screen {
	private float hint = 1;
	private long frame = System.nanoTime();
	private static final String HINT = "ЛКМ: двигать   |   Колесо: масштаб   |   ПКМ: настройки   |   Ctrl+Z: отмена   |   Esc: готово";
	private final Screen parent;
	private final HudInteraction interaction = new HudInteraction();
	public HudEditorScreen(Screen parent) { super(Component.literal("Редактор HUD")); this.parent = parent; }

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) { }

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, 0x40000000);
		VisualHud.render(graphics, true);
		float scale = 1.6f / (float) minecraft.getWindow().getGuiScale();
		float viewWidth = width / scale;
		graphics.pose().pushMatrix();
		graphics.pose().scale(scale, scale);
		float size = Math.min(7.5f, (viewWidth - 40) / AstolfoUi.MEDIUM.width(HINT, 1));
		float textWidth = AstolfoUi.MEDIUM.width(HINT, size);
		float pillX = (viewWidth - textWidth) / 2 - 12;
		float pillY = height / scale - 36;
		// The hint sits where bottom panels live: it steps aside when the cursor comes close.
		boolean near = mouseX / scale > pillX - 30 && mouseX / scale < pillX + textWidth + 54 && mouseY / scale > pillY - 30;
		hint = AstolfoUi.animate(hint, near ? 0.12f : 1, 12, Math.min((System.nanoTime() - frame) / 1e9f, 0.1f));
		frame = System.nanoTime();
		float previous = AstolfoUi.alpha;
		AstolfoUi.alpha = hint;
		AstolfoUi.glass(graphics, pillX, pillY, textWidth + 24, 22, 11, 12, 0x90101014);
		AstolfoUi.shape(graphics, pillX, pillY, textWidth + 24, 22, 11, 0.8f, 0x2AFFFFFF, 0x0CFFFFFF);
		AstolfoUi.MEDIUM.draw(graphics, HINT, pillX + 12, pillY + (22 - size) / 2 - 0.3f, size, Theme.TEXT_SECONDARY);
		AstolfoUi.alpha = previous;
		graphics.pose().popMatrix();
		interaction.draw(graphics, mouseX, mouseY, true);
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
