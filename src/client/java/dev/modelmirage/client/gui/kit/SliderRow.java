package dev.modelmirage.client.gui.kit;

import dev.modelmirage.client.gui.MirageUi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import net.minecraft.client.input.KeyEvent;

/** Inline slider with a value box, as in the reference: label, track, value + unit. */
public final class SliderRow extends Row {
	private final double min, max, step;
	private final String unit;
	private final DoubleSupplier value;
	private final DoubleConsumer changed;
	private float shown = -1, grab;
	private boolean dragging;
	/** Apply only when the drag ends; {@code pending} is the value shown meanwhile. */
	private boolean deferred;
	private double pending;

	public SliderRow(String label, double min, double max, double step, String unit, DoubleSupplier value, DoubleConsumer changed) {
		super(label);
		this.min = min;
		this.max = max;
		this.step = step;
		this.unit = unit;
		this.value = value;
		this.changed = changed;
	}

	/**
	 * Applies the value when the slider is released instead of while dragging, for settings whose change moves the
	 * slider itself (a HUD panel's scale moves the popover beside it).
	 */
	public SliderRow deferred() { deferred = true; return this; }

	private double current() { return deferred && dragging ? pending : value.getAsDouble(); }

	@Override
	protected float labelWidth() { return width * 0.42f - 14; }

	private float boxWidth() { return Math.max(30, MirageUi.MEDIUM.width(format(max) + unit, Theme.CAPTION) + 12); }
	private float trackLeft() { return x + width * 0.42f + 4; }
	private float trackRight() { return right() - boxWidth() - 8; }

	@Override
	protected void renderControl(Surface surface) {
		var graphics = surface.graphics;
		float target = (float) ((current() - min) / (max - min));
		shown = shown < 0 || dragging ? target : MirageUi.animate(shown, target, 20, surface.delta);
		grab = MirageUi.animate(grab, dragging ? 1 : hover, 18, surface.delta);
		float left = trackLeft(), right = trackRight(), cy = centerY();
		float track = right - left;
		MirageUi.shape(graphics, left, cy - 1.5f, track, 3, 1.5f, 0, Theme.TRACK_OFF, Theme.TRACK_OFF);
		MirageUi.shape(graphics, left, cy - 1.5f, Math.max(3, track * shown), 3, 1.5f, 0, Theme.accent(), Theme.accent());
		float knob = 9 + grab;
		float knobX = left + track * shown - knob / 2;
		MirageUi.shape(graphics, knobX - 0.5f, cy - knob / 2 + 0.6f, knob + 1, knob + 1, (knob + 1) / 2, 0, 0x22000000, 0x44000000);
		MirageUi.shape(graphics, knobX, cy - knob / 2, knob, knob, knob / 2, 0, Theme.KNOB, 0xFFF0F0F3);
		String text = format(current()) + unit;
		float box = boxWidth();
		int fill = surface.isFocused(this) ? MirageUi.opacity(Theme.accent(), 0.25f) : Theme.CONTROL;
		MirageUi.shape(graphics, right() - box, cy - 7.5f, box, 15, 4.5f, 0, fill, fill);
		MirageUi.MEDIUM.draw(graphics, text, right() - box / 2 - MirageUi.MEDIUM.width(text, Theme.CAPTION) / 2, cy - Theme.CAPTION / 2 - 0.5f, Theme.CAPTION, Theme.TEXT);
	}

	@Override
	public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) {
		if (button != 0 || !contains(mouseX, mouseY)) return false;
		surface.focus(this);
		if (mouseX >= trackLeft() - 6 && mouseX <= trackRight() + 6) {
			dragging = true;
			surface.capture(this);
			slide(mouseX);
		}
		return true;
	}

	@Override
	public boolean mouseDragged(Surface surface, double mouseX, double mouseY, int button) {
		if (!dragging) return false;
		slide(mouseX);
		return true;
	}

	@Override
	public void mouseReleased(Surface surface, double mouseX, double mouseY, int button) {
		if (deferred && dragging) changed.accept(pending);
		dragging = false;
	}

	@Override
	public boolean keyPressed(Surface surface, KeyEvent event) {
		double direction = event.isLeft() || event.isDown() ? -1 : event.isRight() || event.isUp() ? 1 : 0;
		if (direction == 0) return false;
		changed.accept(Math.clamp(value.getAsDouble() + direction * step * (event.hasShiftDown() ? 10 : 1), min, max));
		return true;
	}

	private void slide(double mouseX) {
		double progress = Math.clamp((mouseX - trackLeft()) / (trackRight() - trackLeft()), 0, 1);
		double raw = min + progress * (max - min);
		double next = Math.clamp(min + Math.round((raw - min) / step) * step, min, max);
		if (deferred) pending = next;
		else changed.accept(next);
	}

	private String format(double number) {
		int scale = Math.max(0, BigDecimal.valueOf(step).stripTrailingZeros().scale());
		return BigDecimal.valueOf(number).setScale(scale, RoundingMode.HALF_UP).toPlainString();
	}
}
