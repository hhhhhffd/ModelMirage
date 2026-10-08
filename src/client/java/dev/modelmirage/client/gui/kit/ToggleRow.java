package dev.modelmirage.client.gui.kit;

import dev.modelmirage.client.gui.AstolfoUi;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.input.KeyEvent;

public final class ToggleRow extends Row {
	private final BooleanSupplier value;
	private final Consumer<Boolean> changed;
	private float progress;

	public ToggleRow(String label, BooleanSupplier value, Consumer<Boolean> changed) {
		super(label);
		this.value = value;
		this.changed = changed;
		progress = value.getAsBoolean() ? 1 : 0;
	}

	@Override
	protected float labelWidth() { return width - Paint.SWITCH_WIDTH - 30; }

	@Override
	protected void renderControl(Surface surface) {
		progress = AstolfoUi.animate(progress, value.getAsBoolean() ? 1 : 0, 16, surface.delta);
		Paint.toggle(surface.graphics, right() - Paint.SWITCH_WIDTH, centerY() - Paint.SWITCH_HEIGHT / 2, progress, hover);
	}

	@Override
	public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) {
		if (button != 0 || !contains(mouseX, mouseY)) return false;
		changed.accept(!value.getAsBoolean());
		surface.focus(this);
		return true;
	}

	@Override
	public boolean keyPressed(Surface surface, KeyEvent event) {
		if (!event.isSelection() && !event.isConfirmation()) return false;
		changed.accept(!value.getAsBoolean());
		return true;
	}
}
