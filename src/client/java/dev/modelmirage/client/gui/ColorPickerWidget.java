package dev.modelmirage.client.gui;

import java.awt.Color;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class ColorPickerWidget extends AbstractWidget {
	private float hue, saturation, value;
	private final IntConsumer changed;
	private boolean hueDrag;
	public ColorPickerWidget(int x, int y, int width, int color, IntConsumer changed) {
		super(x, y, width, 145, Component.literal("Цвет интерфейса"));
		float[] hsv = Color.RGBtoHSB(color >> 16 & 255, color >> 8 & 255, color & 255, null);
		hue = hsv[0]; saturation = hsv[1]; value = hsv[2]; this.changed = changed;
	}
	@Override protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		int x = getX(), y = getY();
		AstolfoUi.SEMIBOLD.draw(graphics, "Цвет интерфейса", x, y, 12, AstolfoUi.TEXT);
		for (int row = 0; row < 48; row++) for (int column = 0; column < 64; column++) {
			int left = x + column * width / 64, right = x + (column + 1) * width / 64;
			int top = y + 23 + row * 93 / 48, bottom = y + 23 + (row + 1) * 93 / 48;
			graphics.fill(left, top, right, bottom, AstolfoUi.opacity(Color.HSBtoRGB(hue, column / 63f, 1 - row / 47f), AstolfoUi.alpha));
		}
		for (int column = 0; column < width; column++) graphics.fill(x + column, y + 125, x + column + 1, y + 136, AstolfoUi.opacity(Color.HSBtoRGB(column / (float) width, 1, 1), AstolfoUi.alpha));
		int cx = x + Math.round(saturation * width), cy = y + 23 + Math.round((1 - value) * 93);
		AstolfoUi.border(graphics, cx - 3, cy - 3, 7, 7, 3, 0xFFFFFFFF);
		graphics.fill(x + Math.round(hue * width) - 1, y + 123, x + Math.round(hue * width) + 1, y + 138, 0xFFFFFFFF);
	}
	@Override public void onClick(MouseButtonEvent event, boolean doubleClick) { hueDrag = event.y() >= getY() + 121; choose(event); }
	@Override protected void onDrag(MouseButtonEvent event, double dx, double dy) { choose(event); }
	private void choose(MouseButtonEvent event) {
		if (hueDrag) hue = (float) Math.clamp((event.x() - getX()) / width, 0, 1);
		else { saturation = (float) Math.clamp((event.x() - getX()) / width, 0, 1); value = 1 - (float) Math.clamp((event.y() - getY() - 23) / 93, 0, 1); }
		changed.accept(Color.HSBtoRGB(hue, saturation, value));
	}
	@Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
}
