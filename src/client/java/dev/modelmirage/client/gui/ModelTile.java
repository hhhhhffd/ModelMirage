package dev.modelmirage.client.gui;

import dev.modelmirage.client.Lang;
import dev.modelmirage.client.catalog.CatalogModel;
import dev.modelmirage.client.catalog.CatalogService;
import dev.modelmirage.client.gui.kit.Control;
import dev.modelmirage.client.gui.kit.Surface;
import dev.modelmirage.client.gui.kit.Theme;
import dev.modelmirage.client.render.MascotAnimations;
import dev.modelmirage.client.render.RuntimeModels;
import java.util.function.BooleanSupplier;
import net.minecraft.world.item.ItemStack;

/** Loadout tile: a vanilla item or a catalog model with its live preview, name and state. */
public final class ModelTile extends Control {
	public static final float HEIGHT = 94;
	public final CatalogModel model;
	public final ItemStack item;
	public final String name;
	public BooleanSupplier selected = () -> false;
	public BooleanSupplier applied = () -> false;
	public float yaw, pitch;
	private final Runnable onClick;
	private float select, press;

	public ModelTile(CatalogModel model, Runnable onClick) {
		this.model = model;
		this.item = ItemStack.EMPTY;
		this.name = model.name();
		this.onClick = onClick;
		height = HEIGHT;
	}

	public ModelTile(ItemStack item, Runnable onClick) {
		this.model = null;
		this.item = item;
		this.name = item.getHoverName().getString();
		this.onClick = onClick;
		height = HEIGHT;
	}

	private String status() {
		if (model == null) return "";
		if (!CatalogService.supports(model)) return Lang.tr("modelmirage.tile.needs_loader");
		if (CatalogService.isLoading(model)) return Lang.tr("modelmirage.state.loading");
		if (CatalogService.hasFailed(model)) return Lang.tr("modelmirage.tile.load_failed");
		// Not downloaded yet: tell the size while its preview is on the way.
		if (!model.builtin() && !CatalogService.isLoaded(model) && !CatalogService.isCached(model)) return groupName(model) + ", " + size(model.bytes());
		return groupName(model);
	}

	public static String size(long bytes) {
		if (bytes < 1024 * 1024) return Lang.tr("modelmirage.size.kb", Math.max(1, Math.round(bytes / 1024.0)));
		return Lang.tr("modelmirage.size.mb", String.format(java.util.Locale.ROOT, "%.1f", bytes / (1024.0 * 1024)));
	}

	/** Catalog models without a group fall back to a default one for their kind. */
	public static String groupName(CatalogModel model) {
		if (!model.group().isEmpty()) return model.group();
		return switch (model.kind()) {
			case ITEM -> Lang.tr("modelmirage.group.item");
			case PLAYER -> Lang.tr("modelmirage.group.player");
			case MASCOT -> Lang.tr("modelmirage.group.mascot");
		};
	}

	public void activate() { onClick.run(); }

	@Override
	public void render(Surface surface) {
		var graphics = surface.graphics;
		updateHover(surface);
		select = MirageUi.animate(select, selected.getAsBoolean() ? 1 : 0, 18, surface.delta);
		press = MirageUi.animate(press, 0, 12, surface.delta);
		graphics.pose().pushMatrix();
		float scale = 1 + hover * 0.015f - press * 0.03f;
		graphics.pose().translate(x + width / 2, y + height / 2);
		graphics.pose().scale(scale, scale);
		graphics.pose().translate(-x - width / 2, -y - height / 2);
		int fill = MirageUi.mix(MirageUi.mix(Theme.CARD, 0x1AFFFFFF, hover), MirageUi.opacity(Theme.accent(), 0.16f), select);
		MirageUi.shape(graphics, x, y, width, height, 10, 0, fill, fill);
		int stroke = MirageUi.mix(Theme.CARD_STROKE, Theme.accent(), select);
		MirageUi.shape(graphics, x, y, width, height, 10, 0.8f + select * 0.6f, stroke, MirageUi.mix(0x0AFFFFFF, Theme.accent(), select));
		float previewHeight = height - 32;
		graphics.enableScissor(Math.round(x + 2), Math.round(y + 2), Math.round(x + width - 2), Math.round(y + previewHeight));
		if (model == null) {
			graphics.pose().pushMatrix();
			float itemScale = 2.4f;
			graphics.pose().translate(x + width / 2 - 8 * itemScale, y + previewHeight / 2 - 8 * itemScale + 3);
			graphics.pose().scale(itemScale, itemScale);
			graphics.item(item, 0, 0);
			graphics.pose().popMatrix();
		} else if (model.kind() == CatalogModel.Kind.MASCOT && CatalogService.isLoaded(model)) {
			float ratio = Math.min(46f / MascotAnimations.width(model.modelId()), 46f / MascotAnimations.height(model.modelId()));
			int w = Math.round(MascotAnimations.width(model.modelId()) * ratio), h = Math.round(MascotAnimations.height(model.modelId()) * ratio);
			MascotAnimations.draw(graphics, model.modelId(), Math.round(x + (width - w) / 2), Math.round(y + 4 + (previewHeight - h) / 2), w, h, MirageUi.opacity(0xFFFFFFFF, MirageUi.alpha));
		} else if (CatalogService.isLoaded(model)) {
			RuntimeModels.preview(graphics, model.modelId(), x + width / 2, y + previewHeight / 2 + 3, 44, yaw, pitch);
		} else {
			float pulse = CatalogService.isLoading(model) ? 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 200.0) : 0;
			MirageUi.shape(graphics, x + width / 2 - 16, y + previewHeight / 2 - 13, 32, 32, 9, 0, 0x14FFFFFF, 0x0AFFFFFF);
			MirageUi.ICONS.draw(graphics, model.kind() == CatalogModel.Kind.PLAYER ? "L" : "E", x + width / 2 - 7, y + previewHeight / 2 - 4, 14, MirageUi.mix(Theme.TEXT_TERTIARY, Theme.TEXT_SECONDARY, pulse));
		}
		graphics.disableScissor();
		MirageUi.SEMIBOLD.draw(graphics, MirageUi.SEMIBOLD.fit(name, Theme.CAPTION + 0.5f, width - 16), x + 8, y + height - 27, Theme.CAPTION + 0.5f, Theme.TEXT);
		String status = status();
		int statusColor = model == null ? Theme.TEXT_TERTIARY : !CatalogService.supports(model) ? Theme.WARNING : CatalogService.hasFailed(model) ? Theme.DANGER : Theme.TEXT_TERTIARY;
		if (!status.isEmpty()) MirageUi.MEDIUM.draw(graphics, MirageUi.MEDIUM.fit(status, 6.5f, width - 16), x + 8, y + height - 16, 6.5f, statusColor);
		if (applied.getAsBoolean()) {
			MirageUi.glow(graphics, x + width - 10, y + 10, 2.5f, Theme.accent(), 0.35f);
			MirageUi.shape(graphics, x + width - 13, y + 7, 6, 6, 3, 0, Theme.accent(), Theme.accent());
		}
		if (model != null && model.color() != 0) MirageUi.shape(graphics, x + 8, y + height - 4, width - 16, 1.5f, 0.75f, 0, MirageUi.opacity(model.color(), 0.7f), MirageUi.opacity(model.color(), 0.7f));
		graphics.pose().popMatrix();
	}

	@Override
	public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) {
		if (button != 0 || !contains(mouseX, mouseY)) return false;
		press = 1;
		onClick.run();
		return true;
	}
}
