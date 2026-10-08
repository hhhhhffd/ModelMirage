package dev.modelmirage.client.gui.kit;

import dev.modelmirage.client.gui.AstolfoUi;
import java.util.function.Supplier;

/** Read-only value on the right. */
public final class InfoRow extends Row {
	private final Supplier<String> value;

	public InfoRow(String label, Supplier<String> value) {
		super(label);
		this.value = value;
	}

	@Override
	protected float labelWidth() { return width * 0.45f; }

	@Override
	protected void renderControl(Surface surface) {
		String text = AstolfoUi.MEDIUM.fit(value.get(), Theme.BODY, width * 0.5f - 16);
		AstolfoUi.MEDIUM.draw(surface.graphics, text, right() - AstolfoUi.MEDIUM.width(text, Theme.BODY), Paint.centeredTextY(y, height, Theme.BODY), Theme.BODY, Theme.TEXT_SECONDARY);
	}
}
