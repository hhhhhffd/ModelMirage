package dev.modelmirage.client.gui.kit;

import dev.modelmirage.client.gui.AstolfoUi;
import java.awt.Color;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Colour swatch that opens a picker: presets, saturation/brightness field and hue strip. */
public final class ColorRow extends Row {
	private static final int[] PRESETS = {0xFFFFFFFF, 0xFF80D6ED, 0xFF0A84FF, 0xFF5E5CE6, 0xFFBF5AF2, 0xFFFF375F, 0xFFFF9F0A, 0xFF30D158};
	private final IntSupplier value;
	private final IntConsumer changed;
	private final Picker picker = new Picker();

	public ColorRow(String label, IntSupplier value, IntConsumer changed) {
		super(label);
		this.value = value;
		this.changed = changed;
	}

	@Override
	protected float labelWidth() { return width - 50; }

	@Override
	protected void renderControl(Surface surface) {
		var graphics = surface.graphics;
		float size = 14 + hover;
		float left = right() - size, top = centerY() - size / 2;
		AstolfoUi.shape(graphics, left - 1.5f, top - 1.5f, size + 3, size + 3, (size + 3) / 2, 0, Theme.CONTROL, Theme.CONTROL);
		int color = value.getAsInt() | 0xFF000000;
		AstolfoUi.shape(graphics, left, top, size, size, size / 2, 0, color, AstolfoUi.mix(color, 0xFF000000, 0.12f));
	}

	@Override
	public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) {
		if (button != 0 || !contains(mouseX, mouseY)) return false;
		if (surface.isOpen(picker)) { surface.close(); return true; }
		picker.sync(value.getAsInt());
		picker.opening = 0;
		picker.anchor(surface, right() - 150, centerY() - 8, 150, 16, 150, 152);
		surface.open(picker);
		return true;
	}

	private final class Picker extends Popup {
		private float hue, saturation, brightness;
		private int dragging;

		void sync(int color) {
			float[] hsb = Color.RGBtoHSB(color >> 16 & 255, color >> 8 & 255, color & 255, null);
			hue = hsb[0];
			saturation = hsb[1];
			brightness = hsb[2];
		}

		private float fieldTop() { return y + 30; }

		@Override
		protected void renderContent(Surface surface) {
			var graphics = surface.graphics;
			for (int i = 0; i < PRESETS.length; i++) {
				float left = x + 10 + i * 17;
				AstolfoUi.shape(graphics, left, y + 9, 13, 13, 6.5f, 0, PRESETS[i], PRESETS[i]);
				if ((value.getAsInt() | 0xFF000000) == PRESETS[i]) AstolfoUi.shape(graphics, left - 2, y + 7, 17, 17, 8.5f, 1.2f, 0xFFFFFFFF, 0xFFFFFFFF);
			}
			float left = x + 10, top = fieldTop(), w = width - 20, h = 88;
			int pure = Color.HSBtoRGB(hue, 1, 1) | 0xFF000000;
			// Two gradient layers give the saturation/brightness field without per-pixel quads.
			for (int column = 0; column < 24; column++) {
				float t0 = column / 24f;
				int top0 = AstolfoUi.mix(0xFFFFFFFF, pure, t0);
				AstolfoUi.shape(graphics, left + w * t0, top, w / 24f + 0.6f, h, 0, 0, top0, 0xFF000000);
			}
			AstolfoUi.shape(graphics, left, top, w, h, 2, 1, 0x33FFFFFF, 0x33FFFFFF);
			float cx = left + saturation * w, cy = top + (1 - brightness) * h;
			AstolfoUi.shape(graphics, cx - 5, cy - 5, 10, 10, 5, 2, 0xFFFFFFFF, 0xFFFFFFFF);
			float hueTop = top + h + 10;
			for (int segment = 0; segment < 24; segment++) {
				float t0 = segment / 24f, t1 = (segment + 1) / 24f;
				AstolfoUi.shape(graphics, left + w * t0, hueTop, w / 24f + 0.6f, 8, 0, 0, Color.HSBtoRGB(t0, 1, 1) | 0xFF000000, Color.HSBtoRGB(t1, 1, 1) | 0xFF000000);
			}
			AstolfoUi.shape(graphics, left, hueTop, w, 8, 2, 1, 0x33FFFFFF, 0x33FFFFFF);
			float hx = left + hue * w;
			AstolfoUi.shape(graphics, hx - 4, hueTop - 2, 8, 12, 4, 0, 0xFFFFFFFF, 0xFFFFFFFF);
		}

		@Override
		public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) {
			if (button != 0) return true;
			if (mouseY >= y + 7 && mouseY < y + 24) {
				int index = (int) Math.floor((mouseX - x - 9) / 17);
				boolean onSwatch = mouseX - (x + 9 + index * 17) < 15;
				if (onSwatch && index >= 0 && index < PRESETS.length) { sync(PRESETS[index]); changed.accept(PRESETS[index]); }
				return true;
			}
			float top = fieldTop(), hueTop = top + 88 + 10;
			if (mouseX < x + 6 || mouseX > x + width - 6) return true;
			if (mouseY >= top - 4 && mouseY < top + 88 + 4) dragging = 1;
			else if (mouseY >= hueTop - 3 && mouseY < hueTop + 11) dragging = 2;
			else return true;
			surface.capture(this);
			pick(mouseX, mouseY);
			return true;
		}

		@Override
		public boolean mouseDragged(Surface surface, double mouseX, double mouseY, int button) {
			if (dragging == 0) return false;
			pick(mouseX, mouseY);
			return true;
		}

		@Override
		public void mouseReleased(Surface surface, double mouseX, double mouseY, int button) { dragging = 0; }

		private void pick(double mouseX, double mouseY) {
			float left = x + 10, w = width - 20;
			if (dragging == 1) {
				saturation = (float) Math.clamp((mouseX - left) / w, 0, 1);
				brightness = 1 - (float) Math.clamp((mouseY - fieldTop()) / 88, 0, 1);
			} else hue = (float) Math.clamp((mouseX - left) / w, 0, 0.999);
			changed.accept(Color.HSBtoRGB(hue, saturation, brightness) | 0xFF000000);
		}
	}
}
