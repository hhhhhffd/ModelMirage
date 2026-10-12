package dev.modelmirage.client.gui.kit;

import com.mojang.blaze3d.platform.InputConstants;
import dev.modelmirage.client.gui.MirageUi;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.minecraft.client.input.KeyEvent;

/** Key binding chip: click, then press a key. Backspace or Delete clears, Escape cancels. */
public final class KeyRow extends Row {
	private final IntSupplier value;
	private final IntConsumer changed;

	public KeyRow(String label, IntSupplier value, IntConsumer changed) {
		super(label);
		this.value = value;
		this.changed = changed;
	}

	public static String keyName(int key) {
		if (key < 0) return "Нет";
		return InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString().toUpperCase(Locale.ROOT);
	}

	@Override
	protected float labelWidth() { return width - 90; }

	@Override
	protected void renderControl(Surface surface) {
		boolean listening = surface.isFocused(this);
		String text = listening ? "Нажми клавишу..." : MirageUi.MEDIUM.fit(keyName(value.getAsInt()), Theme.CAPTION, 60);
		int fill = listening ? MirageUi.opacity(Theme.accent(), 0.3f) : MirageUi.mix(Theme.CONTROL, Theme.CONTROL_HOVER, hover);
		Paint.chip(surface.graphics, text, right(), centerY(), fill, value.getAsInt() < 0 && !listening ? Theme.TEXT_SECONDARY : Theme.TEXT);
	}

	@Override
	public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) {
		if (!contains(mouseX, mouseY)) return false;
		if (surface.isFocused(this)) { surface.focus(null); return true; }
		surface.focus(this);
		return true;
	}

	@Override
	public boolean keyPressed(Surface surface, KeyEvent event) {
		if (event.key() == InputConstants.KEY_BACKSPACE || event.key() == InputConstants.KEY_DELETE) changed.accept(-1);
		else if (!event.isEscape()) changed.accept(event.key());
		surface.focus(null);
		return true;
	}
}
