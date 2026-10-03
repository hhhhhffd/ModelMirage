package dev.modelmirage.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.modelmirage.client.catalog.CatalogModel;
import dev.modelmirage.client.catalog.CatalogService;
import dev.modelmirage.client.render.MascotAnimations;
import dev.modelmirage.client.render.RuntimeModels;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Compact loadout tile; vanilla items and downloaded assets share the same browsing flow. */
public final class ModelCard extends AbstractWidget {
	public final CatalogModel model;
	public final ItemStack item;
	private final Runnable action;
	public boolean selected;
	public boolean applied;
	public float yaw;
	public float pitch;
	private float hover;
	private long frame = System.nanoTime();

	public ModelCard(int x, int y, int width, CatalogModel model, Runnable action) {
		super(x, y, width, 77, Component.literal(model.name()));
		this.model = model; this.item = ItemStack.EMPTY; this.action = action;
	}
	public ModelCard(int x, int y, int width, ItemStack item, Runnable action) {
		super(x, y, width, 77, item.getHoverName());
		this.model = null; this.item = item; this.action = action;
	}
	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		long now = System.nanoTime();
		hover = AstolfoUi.animate(hover, isHoveredOrFocused() ? 1 : 0, 14, Math.min((now - frame) / 1e9f, 0.1f));
		frame = now;
		int x = getX(), y = getY();
		AstolfoUi.rounded(graphics, x, y, width, height, 6, AstolfoUi.mix(selected ? 0xFF243044 : 0xFF151E2B, 0xFF253248, hover));
		AstolfoUi.border(graphics, x, y, width, height, 6, selected || isFocused() ? 0xFF6573FF : 0xFF222D3D);
		int color = model == null ? 0xFF788292 : model.color();
		graphics.fill(x, y + height - 2, x + width, y + height, AstolfoUi.opacity(color, AstolfoUi.alpha));
		graphics.enableScissor(x + 3, y + 3, x + width - 3, y + 56);
		if (model == null) {
			graphics.pose().pushMatrix();
			graphics.pose().translate(x + (width - 40) / 2f, y + 8);
			graphics.pose().scale(2.5f, 2.5f);
			graphics.item(item, 0, 0);
			graphics.pose().popMatrix();
		} else if (model.kind() == CatalogModel.Kind.MASCOT && CatalogService.isLoaded(model)) {
			float ratio = Math.min(46f / MascotAnimations.width(model.modelId()), 46f / MascotAnimations.height(model.modelId()));
			int w = Math.round(MascotAnimations.width(model.modelId()) * ratio), h = Math.round(MascotAnimations.height(model.modelId()) * ratio);
			MascotAnimations.draw(graphics, model.modelId(), x + (width - w) / 2, y + (52 - h) / 2, w, h, AstolfoUi.opacity(0xFFFFFFFF, AstolfoUi.alpha));
		} else if (CatalogService.isLoaded(model)) {
			RuntimeModels.preview(graphics, model.modelId(), x + width / 2f, y + 28, 43, yaw, pitch);
		} else {
			AstolfoUi.ICONS.draw(graphics, model.kind() == CatalogModel.Kind.PLAYER ? "L" : "E", x + (width - 24) / 2f, y + 13, 24, 0xFF778699);
		}
		graphics.disableScissor();
		AstolfoUi.MEDIUM.draw(graphics, AstolfoUi.MEDIUM.fit(getMessage().getString(), 7.5f, width - 10), x + 5, y + 57, 7.5f, AstolfoUi.TEXT);
		String status = applied ? "Применено" : model == null ? "" : !CatalogService.supports(model) ? "Нужен загрузчик" : CatalogService.isLoading(model) ? "Загрузка..." : CatalogService.hasFailed(model) ? "Ошибка загрузки" : CatalogService.isLoaded(model) ? "Готово" : "Загрузить";
		if (!status.isEmpty()) AstolfoUi.MEDIUM.draw(graphics, status, x + 5, y + 67, 6, applied ? 0xFF8C9DFF : 0xFF8A94A3);
	}
	@Override public void onClick(MouseButtonEvent event, boolean doubleClick) { action.run(); }
	@Override public boolean keyPressed(KeyEvent event) {
		if (active && isFocused() && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_SPACE)) { action.run(); return true; }
		return false;
	}
	@Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
}
