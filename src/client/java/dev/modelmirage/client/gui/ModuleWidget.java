package dev.modelmirage.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.modelmirage.client.visual.VisualModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Original ClickGui header: toggle with LMB, bind with RMB/MMB. */
public final class ModuleWidget extends AbstractWidget {
	private final VisualModule module;
	private final Runnable open;
	private final Runnable changed;
	private float toggle;
	private long frame = System.nanoTime();

	public ModuleWidget(int x, int y, int width, VisualModule module, Runnable open, Runnable changed) {
		super(x, y, width, 24, Component.literal(module.name));
		this.module = module;
		this.open = open;
		this.changed = changed;
		toggle = module.enabled ? 1 : 0;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		long now = System.nanoTime();
		toggle = AstolfoUi.animate(toggle, module.enabled ? 1 : 0, 18, Math.min((now - frame) / 1e9f, 0.1f));
		frame = now;
		int x = getX();
		int y = getY();
		if (isHoveredOrFocused()) AstolfoUi.rounded(graphics, x, y, width, height, 7, AstolfoUi.HOVER);
		AstolfoUi.SEMIBOLD.draw(graphics, module.id, x + 10, y + 8, 9.5f, AstolfoUi.TEXT);
		if (module.bind >= 0) {
			String key = InputConstants.Type.KEYSYM.getOrCreate(module.bind).getDisplayName().getString();
			AstolfoUi.MEDIUM.draw(graphics, AstolfoUi.MEDIUM.fit("[" + key + "]", 7, 48), x + 15 + AstolfoUi.SEMIBOLD.width(module.id, 9.5f), y + 9, 7, AstolfoUi.MUTED);
		}
		AstolfoUi.rounded(graphics, x + width - 32, y + 6, 22, 11, 5, AstolfoUi.mix(0xFF14141A, AstolfoUi.ACCENT, toggle));
		AstolfoUi.shape(graphics, x + width - 30.5f + toggle * 11, y + 7.5f, 8, 8, 4, 0, AstolfoUi.TEXT, AstolfoUi.TEXT);

	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) { module.enabled = !module.enabled; changed.run(); }

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (active && visible && isMouseOver(event.x(), event.y()) && (event.button() == 1 || event.button() == 2)) { open.run(); return true; }
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (active && isFocused() && event.key() == InputConstants.KEY_SPACE) { module.enabled = !module.enabled; changed.run(); return true; }
		if (active && isFocused() && event.key() == InputConstants.KEY_RETURN) { open.run(); return true; }
		return false;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
}
