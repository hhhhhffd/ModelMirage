package dev.modelmirage.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.modelmirage.client.visual.VisualModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Neverlose-style row: settings on the left, a directly accessible switch on the right. */
public final class ModuleWidget extends AbstractWidget {
	private final VisualModule module;
	private final Runnable open;
	private final Runnable changed;
	private float toggle;
	private long frame = System.nanoTime();

	public ModuleWidget(int x, int y, int width, VisualModule module, Runnable open, Runnable changed) {
		super(x, y, width, 38, Component.literal(module.name));
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
		if (isHoveredOrFocused()) AstolfoUi.rounded(graphics, x + 2, y + 2, width - 4, height - 4, 4, AstolfoUi.HOVER);
		AstolfoUi.MEDIUM.draw(graphics, AstolfoUi.MEDIUM.fit(getMessage().getString(), 12, width - 77), x + 10, y + 13, 12, AstolfoUi.TEXT);
		AstolfoUi.ICONS.draw(graphics, "J", x + width - 59, y + 13, 12, AstolfoUi.MUTED);
		AstolfoUi.rounded(graphics, x + width - 37, y + 13, 27, 13, 6, AstolfoUi.mix(0xFF283443, AstolfoUi.ACCENT, toggle));
		AstolfoUi.shape(graphics, x + width - 35 + toggle * 13, y + 15, 9, 9, 4, 0, 0xFFF2F6FB, 0xFFF2F6FB);
		graphics.fill(x + 10, y + height - 1, x + width - 10, y + height, 0x501B2B40);
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		if (event.x() >= getX() + width - 41) { module.enabled = !module.enabled; changed.run(); }
		else open.run();
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
