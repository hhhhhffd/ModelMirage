package dev.modelmirage.client.gui.kit;

import dev.modelmirage.client.gui.MirageUi;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Drawing primitives shared by controls, so a switch or a chip looks the same everywhere. */
public final class Paint {
	public static final float SWITCH_WIDTH = 24, SWITCH_HEIGHT = 14;

	private Paint() { }

	/** iOS-style switch; {@code progress} 0 = off, 1 = on. */
	public static void toggle(GuiGraphicsExtractor graphics, float x, float y, float progress, float hover) {
		int track = MirageUi.mix(MirageUi.mix(Theme.TRACK_OFF, 0x4D787880, hover), Theme.accent(), progress);
		MirageUi.shape(graphics, x, y, SWITCH_WIDTH, SWITCH_HEIGHT, SWITCH_HEIGHT / 2, 0, track, track);
		float knob = SWITCH_HEIGHT - 3;
		float knobX = x + 1.5f + (SWITCH_WIDTH - knob - 3) * progress;
		MirageUi.shape(graphics, knobX - 0.5f, y + 2f, knob + 1, knob + 1, (knob + 1) / 2, 0, 0x26000000, 0x40000000);
		MirageUi.shape(graphics, knobX, y + 1.5f, knob, knob, knob / 2, 0, Theme.KNOB, 0xFFF0F0F3);
	}

	/** Small rounded label such as a key binding or a value box. */
	public static float chip(GuiGraphicsExtractor graphics, String text, float right, float centerY, int fill, int color) {
		float width = MirageUi.MEDIUM.width(text, Theme.CAPTION) + 10;
		MirageUi.shape(graphics, right - width, centerY - 7, width, 14, 4, 0, fill, fill);
		MirageUi.MEDIUM.draw(graphics, text, right - width + 5, centerY - Theme.CAPTION / 2 - 0.5f, Theme.CAPTION, color);
		return width;
	}

	/** Chevron made of two short strokes, pointing down ({@code open == 0}) or up ({@code open == 1}). */
	public static void chevron(GuiGraphicsExtractor graphics, float cx, float cy, float open, int color) {
		graphics.pose().pushMatrix();
		graphics.pose().translate(cx, cy);
		graphics.pose().rotate((float) Math.PI * open);
		graphics.pose().pushMatrix();
		graphics.pose().rotate((float) Math.toRadians(45));
		MirageUi.shape(graphics, -3.2f, -0.6f, 4.2f, 1.3f, 0.65f, 0, color, color);
		graphics.pose().popMatrix();
		graphics.pose().pushMatrix();
		graphics.pose().rotate((float) Math.toRadians(-45));
		MirageUi.shape(graphics, -1.0f, -0.6f, 4.2f, 1.3f, 0.65f, 0, color, color);
		graphics.pose().popMatrix();
		graphics.pose().popMatrix();
	}

	public static void separator(GuiGraphicsExtractor graphics, float x, float y, float width) {
		MirageUi.shape(graphics, x, y, width, 0.6f, 0, 0, Theme.SEPARATOR, Theme.SEPARATOR);
	}

	public static float centeredTextY(float top, float height, float size) { return top + (height - size) / 2f - 0.3f; }
}
