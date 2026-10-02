package dev.modelmirage.client.visual;

import dev.modelmirage.client.gui.AstolfoButton;
import dev.modelmirage.client.gui.AstolfoUi;
import dev.modelmirage.client.gui.ColorPickerWidget;
import java.io.IOException;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.LoggerFactory;

public final class AppearanceScreen extends Screen {
	private final Screen parent;
	private AstolfoButton animations;
	private String error = "";

	public AppearanceScreen(Screen parent) {
		super(Component.literal("Оформление"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = width / 2 - 140;
		int y = Math.max(10, height / 2 - 100);
		addRenderableWidget(new ColorPickerWidget(x, y + 25, 125, AstolfoUi.ACCENT, color -> AstolfoUi.ACCENT = color));
		animations = addRenderableWidget(new AstolfoButton(x + 150, y + 25, 125, 27, "Анимации", false,
				() -> VisualPreferences.animations = !VisualPreferences.animations));
		addRenderableWidget(new Slider(x + 150, y + 65, "Масштаб", VisualPreferences.guiScale, 0.75f, 1.5f, value -> VisualPreferences.guiScale = value));
		addRenderableWidget(new Slider(x + 150, y + 108, "Затемнение", VisualPreferences.opacity, 0.65f, 1, value -> VisualPreferences.opacity = value));
		addRenderableWidget(new AstolfoButton(x + 150, y + 158, 125, 29, "Готово", true, this::onClose));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		int x = width / 2 - 153;
		int y = Math.max(2, height / 2 - 113);
		graphics.fill(0, 0, width, height, 0xBB040912);
		AstolfoUi.rounded(graphics, x, y, 306, 221, 7, AstolfoUi.PANEL);
		AstolfoUi.border(graphics, x, y, 306, 221, 7, AstolfoUi.LINE);
		AstolfoUi.BOLD.draw(graphics, "Оформление", x + 13, y + 13, 13, AstolfoUi.TEXT);
		animations.selected = VisualPreferences.animations;
		animations.setMessage(Component.literal("Анимации: " + (VisualPreferences.animations ? "Вкл" : "Выкл")));
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		if (!error.isEmpty()) AstolfoUi.MEDIUM.draw(graphics, error, x + 13, y + 205, 9, 0xFFFF7C8F);
	}

	@Override
	public void onClose() {
		try {
			VisualPreferences.save();
			minecraft.gui.setScreen(parent);
		} catch (IOException failure) {
			error = "Не удалось сохранить оформление";
			LoggerFactory.getLogger("ModelMirage").error("Could not save appearance", failure);
		}
	}

	private static final class Slider extends AbstractSliderButton {
		private final String label;
		private final float min;
		private final float max;
		private final Consumer<Float> changed;

		Slider(int x, int y, String label, float value, float min, float max, Consumer<Float> changed) {
			super(x, y, 125, 28, Component.literal(label), (value - min) / (max - min));
			this.label = label;
			this.min = min;
			this.max = max;
			this.changed = changed;
			updateMessage();
		}

		@Override
		public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
			AstolfoUi.rounded(graphics, getX(), getY(), width, height, 4, AstolfoUi.CARD);
			if (isFocused()) AstolfoUi.border(graphics, getX(), getY(), width, height, 4, AstolfoUi.ACCENT);
			AstolfoUi.MEDIUM.draw(graphics, getMessage().getString(), getX() + 7, getY() + 5, 9, AstolfoUi.TEXT);
			AstolfoUi.rounded(graphics, getX() + 7, getY() + 21, width - 14, 3, 1, AstolfoUi.LINE);
			AstolfoUi.rounded(graphics, getX() + 7, getY() + 21, Math.max(1, (int) ((width - 14) * value)), 3, 1, AstolfoUi.ACCENT);
			AstolfoUi.shape(graphics, getX() + 4 + (width - 14) * (float) value, getY() + 18, 7, 7, 3, 0, AstolfoUi.TEXT, AstolfoUi.TEXT);
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(label + ": " + String.format(Locale.ROOT, "%.2f", min + value * (max - min))));
		}

		@Override
		protected void applyValue() { changed.accept((float) (min + value * (max - min))); }
	}
}
