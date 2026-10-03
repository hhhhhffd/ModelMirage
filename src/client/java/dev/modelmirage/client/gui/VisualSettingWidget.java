package dev.modelmirage.client.gui;

import com.google.gson.JsonPrimitive;
import dev.modelmirage.client.visual.VisualModule;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class VisualSettingWidget extends AbstractWidget {
	public final VisualModule.Setting setting;
	private final Runnable changed;
	private float toggle;
	private long frame = System.nanoTime();

	public VisualSettingWidget(int x, int y, int width, VisualModule.Setting setting,
			Runnable changed) {
		super(x, y, width, setting.type.equals("number") ? 34 : 23, Component.literal(setting.name));
		this.setting = setting;
		this.changed = changed;
		toggle = setting.type.equals("boolean") && setting.value.getAsBoolean() ? 1 : 0;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		long now = System.nanoTime();
		float seconds = Math.min((now - frame) / 1e9f, 0.1f);
		frame = now;
		int x = getX();
		int y = getY();
		if (isHoveredOrFocused()) AstolfoUi.rounded(graphics, x, y, width, height, 4, 0x280A0A0E);
		float available = width - 50;
		AstolfoUi.MEDIUM.draw(graphics, AstolfoUi.MEDIUM.fit(setting.name, 8, available), x + 10, y + 8, 8, AstolfoUi.TEXT);
		if (setting.type.equals("boolean")) {
			toggle = AstolfoUi.animate(toggle, setting.value.getAsBoolean() ? 1 : 0, 18, seconds);
			AstolfoUi.rounded(graphics, x + width - 36, y + 7, 22, 11, 5, AstolfoUi.mix(0xFF14141A, AstolfoUi.ACCENT, toggle));
			AstolfoUi.shape(graphics, x + width - 34.5f + toggle * 11, y + 8.5f, 8, 8, 4, 0, 0xFFF2F6FB, 0xFFF2F6FB);
		} else if (setting.type.equals("number")) {
			String value = format(setting.value.getAsDouble());
			AstolfoUi.MEDIUM.draw(graphics, value, x + width - 10 - AstolfoUi.MEDIUM.width(value, 8), y + 8, 8, AstolfoUi.ACCENT);
			int barX = x + 10;
			int barWidth = width - 20;
			float progress = (float) ((setting.value.getAsDouble() - setting.min) / (setting.max - setting.min));
			AstolfoUi.rounded(graphics, barX, y + 25, barWidth, 3, 1, 0xFF14141A);
			AstolfoUi.rounded(graphics, barX, y + 25, Math.max(1, Math.round(barWidth * progress)), 3, 1, AstolfoUi.ACCENT);
			AstolfoUi.shape(graphics, barX + barWidth * progress - 4, y + 22, 8, 8, 4, 0, AstolfoUi.TEXT, AstolfoUi.TEXT);
		}
		graphics.fill(x + 9, y + height - 1, x + width - 9, y + height, 0x5516161C);
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		switch (setting.type) {
			case "boolean" -> { setting.set(new JsonPrimitive(!setting.value.getAsBoolean())); changed.run(); }
			case "number" -> slide(event.x());
		}
	}

	@Override
	protected void onDrag(MouseButtonEvent event, double dx, double dy) {
		if (setting.type.equals("number")) slide(event.x());
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!active || !isFocused()) return false;
		if (setting.type.equals("boolean") && (event.key() == InputConstants.KEY_SPACE || event.key() == InputConstants.KEY_RETURN)) {
			setting.set(new JsonPrimitive(!setting.value.getAsBoolean()));
			changed.run();
			return true;
		}
		if (setting.type.equals("number") && (event.key() == InputConstants.KEY_LEFT || event.key() == InputConstants.KEY_RIGHT)) {
			setting.set(new JsonPrimitive(setting.value.getAsDouble() + (event.key() == InputConstants.KEY_LEFT ? -setting.step : setting.step)));
			changed.run();
			return true;
		}
		return false;
	}

	private void slide(double x) {
		double progress = Math.clamp((x - getX() - 10) / (width - 20), 0, 1);
		setting.set(new JsonPrimitive(setting.min + progress * (setting.max - setting.min)));
		changed.run();
	}

	private static String format(double value) {
		return java.math.BigDecimal.valueOf(value).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
}
