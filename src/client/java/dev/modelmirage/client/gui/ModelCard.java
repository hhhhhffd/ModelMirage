package dev.modelmirage.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import dev.modelmirage.client.catalog.CatalogModel;
import dev.modelmirage.client.catalog.CatalogService;
import dev.modelmirage.client.render.RuntimeModels;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class ModelCard extends AbstractWidget {
	public final CatalogModel model;
	private final Runnable action;
	public boolean selected;
	public boolean applied;

	public ModelCard(int x, int y, CatalogModel model, Runnable action) {
		super(x, y, 124, 123, Component.literal(model.name()));
		this.model = model;
		this.action = action;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		int x = getX();
		int y = getY();
		AstolfoUi.rounded(graphics, x, y, width, height, 6, selected ? AstolfoUi.SELECTED : isHoveredOrFocused() ? AstolfoUi.HOVER : AstolfoUi.PANEL);
		AstolfoUi.border(graphics, x, y, width, height, 6, selected || isFocused() ? AstolfoUi.ACCENT : AstolfoUi.LINE);
		graphics.fill(x + 1, y + 10, x + 3, y + 85, AstolfoUi.ACCENT);
		graphics.enableScissor(x + 5, y + 5, x + width - 5, y + 84);
		if (CatalogService.isLoaded(model)) RuntimeModels.preview(graphics, model.modelId(), x + width / 2f, y + 43, 64, 0, 0);
		else {
			AstolfoUi.ICONS.draw(graphics, "E", x + 47, y + 26, 29, AstolfoUi.MUTED);
			AstolfoUi.MEDIUM.draw(graphics, "Загрузить", x + 36, y + 64, 10, AstolfoUi.MUTED);
		}
		graphics.disableScissor();
		AstolfoUi.SEMIBOLD.draw(graphics, AstolfoUi.SEMIBOLD.fit(model.name(), 11, width - 16), x + 8, y + 89, 11, AstolfoUi.TEXT);
		AstolfoUi.MEDIUM.draw(graphics, applied ? "Применена" : selected ? "Выбрана" : "Модель предмета", x + 8, y + 108, 9, applied || selected ? AstolfoUi.ACCENT : AstolfoUi.MUTED);
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) { action.run(); }
	@Override
	public boolean keyPressed(KeyEvent event) {
		if (active && isFocused() && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_SPACE)) {
			action.run();
			return true;
		}
		return false;
	}
	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
}
