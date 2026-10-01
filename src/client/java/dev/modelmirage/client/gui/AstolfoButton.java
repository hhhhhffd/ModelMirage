package dev.modelmirage.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public final class AstolfoButton extends AbstractWidget {
	private final Runnable action;
	private final boolean primary;
	private float hover;
	private long frame = System.nanoTime();
	public boolean selected;
	public String subtitle = "";
	public String symbol = "";
	public ItemStack item = ItemStack.EMPTY;

	public AstolfoButton(int x, int y, int width, int height, String title, boolean primary, Runnable action) {
		super(x, y, width, height, Component.literal(title));
		this.action = action;
		this.primary = primary;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		long now = System.nanoTime();
		hover = AstolfoUi.animate(hover, active && isHoveredOrFocused() ? 1f : 0f, 16f, Math.min((now - frame) / 1e9f, 0.1f));
		frame = now;
		int color = primary && active ? AstolfoUi.ACCENT : selected ? AstolfoUi.SELECTED : AstolfoUi.CARD;
		AstolfoUi.rounded(graphics, getX(), getY(), width, height, 6,
				AstolfoUi.mix(color, primary ? 0xFFFF85C3 : AstolfoUi.HOVER, hover));
		if (isFocused()) {
			graphics.outline(getX(), getY(), width, height, AstolfoUi.ACCENT);
		}
		int textColor = !active ? 0xFF595968 : primary ? 0xFF210F1B : AstolfoUi.TEXT;
		int start = getX() + 10;
		if (!item.isEmpty()) {
			graphics.item(item, getX() + 8, getY() + (height - 16) / 2);
			start += 25;
		} else if (!symbol.isEmpty()) {
			AstolfoUi.ICONS.draw(graphics, symbol, getX() + 10, getY() + (height - 13) / 2, 13, selected ? AstolfoUi.ACCENT : textColor);
			start += 22;
		}
		boolean centered = subtitle.isEmpty() && symbol.isEmpty() && item.isEmpty();
		String label = AstolfoUi.SEMIBOLD.fit(getMessage().getString(), 11, centered ? width - 6 : width - (start - getX()) - 8);
		float textX = centered ? getX() + (width - AstolfoUi.SEMIBOLD.width(label, 11)) / 2 : start;
		AstolfoUi.SEMIBOLD.draw(graphics, label, textX, getY() + (subtitle.isEmpty() ? (height - 11) / 2f : 9), 11, textColor);
		if (!subtitle.isEmpty()) {
			AstolfoUi.MEDIUM.draw(graphics, AstolfoUi.MEDIUM.fit(subtitle, 9, width - (start - getX()) - 8), start, getY() + 24, 9, AstolfoUi.MUTED);
		}
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) { action.run(); }

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (active && isFocused() && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_SPACE)) {
			action.run();
			return true;
		}
		return false;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
}
