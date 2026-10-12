package dev.modelmirage.client.visual;

import dev.modelmirage.client.Lang;
import dev.modelmirage.client.gui.MirageUi;
import dev.modelmirage.client.gui.kit.Group;
import dev.modelmirage.client.gui.kit.Surface;
import dev.modelmirage.client.gui.kit.Theme;
import dev.modelmirage.client.gui.kit.ToggleRow;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class HudEditorScreen extends Screen {
	private float hint = 1;
	private long frame = System.nanoTime();
	private final String legend = Lang.tr("modelmirage.hud.editor.legend");
	private final Screen parent;
	private final HudInteraction interaction = new HudInteraction(true);
	/** Every HUD element with only its on/off switch; settings stay in the right-click popover. */
	private final Group elements = new Group().title(Lang.tr("modelmirage.hud.editor.elements"));
	private final Surface listSurface = new Surface();
	private float listScale = 1;

	public HudEditorScreen(Screen parent) {
		super(Component.translatable("modelmirage.hud.editor"));
		this.parent = parent;
		for (VisualModule module : Visuals.modules()) {
			if (!module.isPanel() && !module.category.equals("mascot") && !module.id.equals("Crosshair")) continue;
			elements.add(new ToggleRow(module.name(), () -> module.enabled, value -> { module.enabled = value; interaction.settingsChanged(); }));
		}
	}

	private void drawElements(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		listScale = 1.6f / (float) minecraft.getWindow().getGuiScale();
		float viewWidth = width / listScale, viewHeight = height / listScale;
		float listWidth = 150;
		float listHeight = elements.layout(0, 0, listWidth);
		// Docked to the right edge, centred vertically.
		elements.layout(viewWidth - listWidth - 10, Math.max(10, (viewHeight - listHeight) / 2), listWidth);
		graphics.pose().pushMatrix();
		graphics.pose().scale(listScale, listScale);
		listSurface.begin(graphics, mouseX / listScale, mouseY / listScale);
		MirageUi.shadow(graphics, elements.x, elements.y, elements.width, elements.height, Theme.RADIUS_CARD);
		MirageUi.glass(graphics, elements.x, elements.y, elements.width, elements.height, Theme.RADIUS_CARD, VisualPreferences.blur, Theme.POPUP_TINT);
		elements.render(listSurface);
		graphics.pose().popMatrix();
	}

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
		float size = Math.min(7.5f, (viewWidth - 40) / MirageUi.MEDIUM.width(legend, 1));
		float textWidth = MirageUi.MEDIUM.width(legend, size);
		float pillX = (viewWidth - textWidth) / 2 - 12;
		float pillY = height / scale - 36;
		// The hint sits where bottom panels live: it steps aside when the cursor comes close.
		boolean near = mouseX / scale > pillX - 30 && mouseX / scale < pillX + textWidth + 54 && mouseY / scale > pillY - 30;
		hint = MirageUi.animate(hint, near ? 0.12f : 1, 12, Math.min((System.nanoTime() - frame) / 1e9f, 0.1f));
		frame = System.nanoTime();
		float previous = MirageUi.alpha;
		MirageUi.alpha = hint;
		MirageUi.glass(graphics, pillX, pillY, textWidth + 24, 22, 11, 12, 0x90101014);
		MirageUi.shape(graphics, pillX, pillY, textWidth + 24, 22, 11, 0.8f, 0x2AFFFFFF, 0x0CFFFFFF);
		MirageUi.MEDIUM.draw(graphics, legend, pillX + 12, pillY + (22 - size) / 2 - 0.3f, size, Theme.TEXT_SECONDARY);
		MirageUi.alpha = previous;
		graphics.pose().popMatrix();
		drawElements(graphics, mouseX, mouseY);
		interaction.draw(graphics, mouseX, mouseY, true);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double x = event.x() / listScale, y = event.y() / listScale;
		if (!interaction.popoverOpen() && elements.contains(x, y)) { listSurface.mouseClicked(List.of(elements), x, y, event.button()); return true; }
		return interaction.click(event, doubleClick, width, height);
	}
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
