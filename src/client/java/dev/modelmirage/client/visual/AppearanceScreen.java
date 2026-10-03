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
import net.minecraft.client.input.MouseButtonEvent;
import org.slf4j.LoggerFactory;

public final class AppearanceScreen extends Screen {
	private final Screen parent;
	private AstolfoButton animations;
	private String error = "";
	private float opening;
	private float scale = 1;
	private float originX;
	private float originY;
	private boolean closing;
	private long frame = System.nanoTime();

	public AppearanceScreen(Screen parent) {
		super(Component.literal("Оформление"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = 13;
		int y = 13;
		addRenderableWidget(new ColorPickerWidget(x, y + 25, 125, AstolfoUi.ACCENT, color -> AstolfoUi.ACCENT = color));
		animations = addRenderableWidget(new AstolfoButton(x + 150, y + 25, 125, 27, "Анимации", false,
				() -> VisualPreferences.animations = !VisualPreferences.animations));
		addRenderableWidget(new Slider(x + 150, y + 65, "Масштаб", VisualPreferences.guiScale, 0.75f, 1.5f, value -> VisualPreferences.guiScale = value));
		addRenderableWidget(new Slider(x + 150, y + 108, "Затемнение", VisualPreferences.opacity, 0.65f, 1, value -> VisualPreferences.opacity = value));
		addRenderableWidget(new AstolfoButton(x + 150, y + 158, 125, 29, "Готово", true, this::onClose));
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) { }

	@Override
	public boolean isPauseScreen() { return false; }

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		long now = System.nanoTime();
		opening = AstolfoUi.animate(opening, closing ? 0 : 1, 18, Math.min((now - frame) / 1e9f, 0.1f));
		frame = now;
		if (closing && opening < 0.005f) { minecraft.gui.setScreen(parent); return; }
		parent.extractRenderState(graphics, -1000, -1000, delta);
		graphics.fill(0, 0, width, height, AstolfoUi.opacity(0x77000000, opening));
		scale = Math.min((float) (2f / minecraft.getWindow().getGuiScale()) * VisualPreferences.guiScale, Math.min((width - 16f) / 306, (height - 16f) / 221)) * (0.90f + opening * 0.10f);
		originX = (width - 306 * scale) / 2;
		originY = (height - 221 * scale) / 2;
		graphics.pose().pushMatrix();
		graphics.pose().translate(originX, originY);
		graphics.pose().scale(scale, scale);
		AstolfoUi.alpha = opening;
		AstolfoUi.shadow(graphics, 0, 0, 306, 221, 7);
		AstolfoUi.rounded(graphics, 0, 0, 306, 221, 7, AstolfoUi.BACKGROUND);
		AstolfoUi.border(graphics, 0, 0, 306, 221, 7, AstolfoUi.LINE);
		AstolfoUi.BOLD.draw(graphics, "Оформление", 13, 13, 13, AstolfoUi.TEXT);
		animations.selected = VisualPreferences.animations;
		animations.setMessage(Component.literal("Анимации: " + (VisualPreferences.animations ? "Вкл" : "Выкл")));
		super.extractRenderState(graphics, (int) ((mouseX - originX) / scale), (int) ((mouseY - originY) / scale), delta);
		if (!error.isEmpty()) AstolfoUi.MEDIUM.draw(graphics, error, 13, 205, 9, 0xFFFF7C8F);
		AstolfoUi.alpha = 1;
		graphics.pose().popMatrix();
	}

	private MouseButtonEvent local(MouseButtonEvent event) {
		return new MouseButtonEvent((event.x() - originX) / scale, (event.y() - originY) / scale, event.buttonInfo());
	}
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) { return !closing && super.mouseClicked(local(event), doubleClick); }
	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) { return !closing && super.mouseDragged(local(event), dx / scale, dy / scale); }
	@Override
	public boolean mouseReleased(MouseButtonEvent event) { return super.mouseReleased(local(event)); }

	@Override
	public void onClose() {
		try {
			VisualPreferences.save();
			if (VisualPreferences.animations) closing = true;
			else minecraft.gui.setScreen(parent);
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
