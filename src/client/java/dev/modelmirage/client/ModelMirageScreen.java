package dev.modelmirage.client;

import dev.modelmirage.client.catalog.CatalogModel;
import dev.modelmirage.client.catalog.CatalogService;
import dev.modelmirage.client.gui.AstolfoButton;
import dev.modelmirage.client.gui.AstolfoUi;
import dev.modelmirage.client.render.RuntimeModels;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.LoggerFactory;

/** Adaptation of AstolfoVisuals ClickGuiScreen's sidebar, two-column layout and animation. */
public final class ModelMirageScreen extends Screen {
	private static final int W = 580;
	private static final int H = 340;
	private static final int SIDEBAR = 138;
	private static final int ROWS = 6;
	private final List<Item> allItems = BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR)
			.sorted((a, b) -> BuiltInRegistries.ITEM.getKey(a).compareTo(BuiltInRegistries.ITEM.getKey(b))).toList();
	private final AstolfoButton[] rows = new AstolfoButton[ROWS];
	private List<Item> filteredItems = List.of();
	private List<CatalogModel> filteredModels = List.of();
	private List<CatalogModel> catalogSnapshot = List.of();
	private EditBox search;
	private AstolfoButton itemsNav;
	private AstolfoButton modelsNav;
	private AstolfoButton appliedNav;
	private AstolfoButton refresh;
	private AstolfoButton apply;
	private AstolfoButton reset;
	private AstolfoButton previous;
	private AstolfoButton next;
	private Page page = Page.ITEMS;
	private Item selectedItem;
	private CatalogModel selectedModel;
	private int offset;
	private float scale;
	private float originX;
	private float originY;
	private float opening;
	private float tabY = 80;
	private float yaw;
	private float pitch;
	private float zoom = 1;
	private boolean dragging;
	private boolean downloading;
	private boolean downloadFailed;
	private String status = "";
	private long frame = System.nanoTime();

	public ModelMirageScreen() { super(Component.literal("ModelMirage")); }

	@Override
	protected void init() {
		String query = search == null ? "" : search.getValue();
		search = new EditBox(font, 428, 18, 108, 14, Component.literal("Поиск"));
		search.setBordered(false);
		search.setMaxLength(128);
		search.setHint(Component.literal("Поиск…"));
		search.setValue(query);
		search.setResponder(value -> { offset = 0; filter(); });
		addRenderableWidget(search);
		itemsNav = button(12, 80, 114, 28, "Предметы", false, () -> show(Page.ITEMS));
		itemsNav.symbol = "F";
		modelsNav = button(12, 114, 114, 28, "Модели", false, () -> show(Page.MODELS));
		modelsNav.symbol = "E";
		appliedNav = button(12, 148, 114, 28, "Применённые", false, () -> show(Page.APPLIED));
		appliedNav.symbol = "L";
		refresh = button(12, 206, 114, 28, "Обновить", false, () -> CatalogService.refresh());
		refresh.symbol = "J";
		button(552, 8, 20, 26, "x", false, this::onClose);
		for (int index = 0; index < ROWS; index++) {
			int row = index;
			rows[index] = button(150, 58 + index * 41, 206, 37, "", false, () -> clickRow(row));
		}
		previous = button(150, 309, 24, 21, "<", false, () -> { offset = Math.max(0, offset - ROWS); updateRows(); });
		next = button(332, 309, 24, 21, ">", false, () -> { offset += ROWS; updateRows(); });
		apply = button(380, 288, 110, 30, "Применить", true, this::applyOrRetry);
		reset = button(496, 288, 66, 30, "Сброс", false, this::resetModel);
		button(459, 250, 103, 22, "Сбросить вид", false, () -> { yaw = 0; pitch = 0; zoom = 1; });
		catalogSnapshot = CatalogService.models();
		filter();
	}

	private AstolfoButton button(int x, int y, int width, int height, String label, boolean primary, Runnable action) {
		return addRenderableWidget(new AstolfoButton(x, y, width, height, label, primary, action));
	}

	@Override
	public void tick() {
		if (catalogSnapshot != CatalogService.models()) {
			catalogSnapshot = CatalogService.models();
			if (selectedModel != null) {
				CatalogModel updated = catalogSnapshot.stream().filter(model -> model.id().equals(selectedModel.id())).findFirst().orElse(null);
				if (updated == null) {
					selectedModel = null;
					downloading = false;
					downloadFailed = false;
					status = "Модель удалена из каталога";
				}
				else if (!updated.equals(selectedModel)) selectModel(updated);
			}
			filter();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		long now = System.nanoTime();
		float seconds = Math.min((now - frame) / 1e9f, 0.1f);
		frame = now;
		opening = AstolfoUi.animate(opening, 1f, 14f, seconds);
		scale = Math.min(1f, Math.min((width - 12f) / W, (height - 12f) / H)) * (0.92f + 0.08f * opening);
		originX = (width - W * scale) / 2;
		originY = (height - H * scale) / 2;
		int mx = (int) ((mouseX - originX) / scale);
		int my = (int) ((mouseY - originY) / scale);
		graphics.fill(0, 0, width, height, 0xAD08080C);
		graphics.pose().pushMatrix();
		graphics.pose().translate(originX, originY);
		graphics.pose().scale(scale, scale);
		for (int i = 5; i >= 1; i--) AstolfoUi.rounded(graphics, -i, -i, W + i * 2, H + i * 2, 10 + i, 0x14000000);
		AstolfoUi.rounded(graphics, 0, 0, W, H, 10, AstolfoUi.BACKGROUND);
		graphics.fill(SIDEBAR, 0, SIDEBAR + 1, H, AstolfoUi.PANEL);
		graphics.fill(SIDEBAR, 42, W, 43, AstolfoUi.PANEL);
		AstolfoUi.SEMIBOLD.draw(graphics, "ModelMirage", 16, 18, 17, AstolfoUi.TEXT);
		AstolfoUi.MEDIUM.draw(graphics, "SKINCHANGER", 16, 40, 8, AstolfoUi.ACCENT);
		AstolfoUi.MEDIUM.draw(graphics, "БИБЛИОТЕКА", 16, 63, 9, AstolfoUi.MUTED);
		tabY = AstolfoUi.animate(tabY, page == Page.ITEMS ? 80 : page == Page.MODELS ? 114 : 148, 18, seconds);
		AstolfoUi.rounded(graphics, 10, Math.round(tabY) - 2, 118, 32, 7, AstolfoUi.SELECTED);
		itemsNav.selected = page == Page.ITEMS;
		modelsNav.selected = page == Page.MODELS;
		modelsNav.active = selectedItem != null;
		appliedNav.selected = page == Page.APPLIED;
		refresh.active = !CatalogService.loading();
		refresh.setMessage(Component.literal(CatalogService.loading() ? "Загрузка..." : "Обновить"));
		AstolfoUi.MEDIUM.draw(graphics, "КАТАЛОГ", 16, 187, 9, AstolfoUi.MUTED);
		String connection = CatalogService.loading() ? "Подключение..." : CatalogService.cached() ? "Доступен из кеша" : CatalogService.error().isEmpty() ? "GitHub: " + catalogSnapshot.size() : "Нет соединения";
		AstolfoUi.MEDIUM.draw(graphics, connection, 16, 241, 9, AstolfoUi.MUTED);
		graphics.fill(12, 267, 126, 268, AstolfoUi.PANEL);
		AstolfoUi.MEDIUM.draw(graphics, "ВЫБРАННЫЙ ПРЕДМЕТ", 16, 279, 8, AstolfoUi.MUTED);
		if (selectedItem != null) {
			graphics.item(new ItemStack(selectedItem), 16, 297);
			AstolfoUi.SEMIBOLD.draw(graphics, AstolfoUi.SEMIBOLD.fit(itemName(selectedItem), 10, 84), 38, 301, 10, AstolfoUi.TEXT);
		} else AstolfoUi.MEDIUM.draw(graphics, "Выбери из списка", 16, 300, 10, AstolfoUi.MUTED);
		AstolfoUi.MEDIUM.draw(graphics, "Применено: " + ItemModelOverrides.count(), 16, 323, 8, AstolfoUi.MUTED);
		AstolfoUi.SEMIBOLD.draw(graphics, page == Page.MODELS ? "Модели предметов" : page == Page.APPLIED ? "Применённые модели" : "Выбери предмет", 154, 18, 14, AstolfoUi.TEXT);
		AstolfoUi.rounded(graphics, 410, 10, 133, 25, 6, AstolfoUi.CARD);
		AstolfoUi.ICONS.draw(graphics, "I", 415, 18, 11, AstolfoUi.MUTED);

		AstolfoUi.rounded(graphics, 368, 54, 200, 222, 8, AstolfoUi.PANEL);
		AstolfoUi.MEDIUM.draw(graphics, "ПРЕДПРОСМОТР", 380, 66, 9, AstolfoUi.MUTED);
		graphics.enableScissor(374, 87, 562, 223);
		if (selectedModel != null && CatalogService.isLoaded(selectedModel)) {
			RuntimeModels.preview(graphics, selectedModel.modelId(), 468, 153, 116 * zoom, yaw, pitch);
		} else if (selectedItem != null && selectedModel == null && !downloading) {
			graphics.pose().pushMatrix();
			graphics.pose().translate(468, 153);
			graphics.pose().scale(5, 5);
			graphics.item(new ItemStack(selectedItem), -8, -8);
			graphics.pose().popMatrix();
		} else {
			AstolfoUi.MEDIUM.draw(graphics, downloadFailed ? "Ошибка загрузки" : downloading ? "Загрузка модели..." : "Выбери предмет", 394, 145, 11, AstolfoUi.MUTED);
		}
		graphics.disableScissor();
		if (selectedModel != null) {
			AstolfoUi.SEMIBOLD.draw(graphics, AstolfoUi.SEMIBOLD.fit(selectedModel.name(), 12, 180), 380, 229, 12, AstolfoUi.TEXT);
		} else AstolfoUi.MEDIUM.draw(graphics, selectedItem == null ? "" : "Затем выбери модель", 380, 229, 11, AstolfoUi.MUTED);
		AstolfoUi.MEDIUM.draw(graphics, "Тяни для вращения", 380, 255, 8, AstolfoUi.MUTED);
		apply.active = selectedModel != null && !downloading && (downloadFailed || CatalogService.isLoaded(selectedModel));
		apply.setMessage(Component.literal(downloadFailed ? "Повторить" : downloading ? "Загрузка..." : "Применить"));
		reset.active = selectedItem != null && ItemModelOverrides.isEnabled(itemId(selectedItem));
		String info = !status.isEmpty() ? status : !CatalogService.error().isEmpty() ? "Каталог недоступен" : "Колесо: масштаб";
		AstolfoUi.MEDIUM.draw(graphics, AstolfoUi.MEDIUM.fit(info, 9, 182), 380, 325, 9, downloadFailed ? 0xFFFFA1B8 : AstolfoUi.MUTED);
		int count = page == Page.MODELS ? filteredModels.size() : filteredItems.size();
		if (count == 0) {
			AstolfoUi.SEMIBOLD.draw(graphics, page == Page.MODELS ? CatalogService.loading() ? "Загрузка каталога..." : "Модели не найдены" : "Предметы не найдены", 160, 78, 12, AstolfoUi.TEXT);
			AstolfoUi.MEDIUM.draw(graphics, !CatalogService.error().isEmpty() && page == Page.MODELS ? "Проверь сеть и нажми Обновить" : "Попробуй другой поиск", 160, 98, 10, AstolfoUi.MUTED);
		}
		AstolfoUi.MEDIUM.draw(graphics, count == 0 ? "0" : (offset + 1) + "-" + Math.min(offset + ROWS, count) + " / " + count, 218, 315, 9, AstolfoUi.MUTED);
		for (int index = 0; index < ROWS; index++) {
			int position = offset + index;
			rows[index].selected = page == Page.MODELS ? position < count && filteredModels.get(position).equals(selectedModel)
					: position < count && filteredItems.get(position) == selectedItem;
		}
		super.extractRenderState(graphics, mx, my, delta);
		graphics.pose().popMatrix();
	}

	private void show(Page nextPage) {
		if (nextPage == Page.MODELS && selectedItem == null) return;
		page = nextPage;
		offset = 0;
		search.setValue("");
		filter();
	}

	private void filter() {
		String query = search.getValue().trim().toLowerCase(Locale.ROOT);
		filteredItems = allItems.stream().filter(item -> page != Page.APPLIED || ItemModelOverrides.isEnabled(itemId(item)))
				.filter(item -> itemId(item).toString().contains(query) || itemName(item).toLowerCase(Locale.ROOT).contains(query)).toList();
		filteredModels = catalogSnapshot.stream().filter(model -> model.id().contains(query) || model.name().toLowerCase(Locale.ROOT).contains(query)).toList();
		updateRows();
	}

	private void updateRows() {
		int count = page == Page.MODELS ? filteredModels.size() : filteredItems.size();
		offset = Math.clamp(offset, 0, Math.max(0, count - 1));
		for (int index = 0; index < ROWS; index++) {
			int position = offset + index;
			AstolfoButton row = rows[index];
			row.visible = position < count;
			row.item = ItemStack.EMPTY;
			row.symbol = "";
			if (!row.visible) continue;
			if (page == Page.MODELS) {
				CatalogModel model = filteredModels.get(position);
				row.setMessage(Component.literal(model.name()));
				row.subtitle = CatalogService.isLoaded(model) ? "Готова к применению" : "Загрузить модель";
				row.symbol = "F";
			} else {
				Item item = filteredItems.get(position);
				row.setMessage(Component.literal(itemName(item)));
				row.subtitle = itemId(item).toString();
				row.item = new ItemStack(item);
			}
		}
		previous.active = offset > 0;
		next.active = offset + ROWS < count;
	}

	private void clickRow(int row) {
		int position = offset + row;
		if (page == Page.MODELS) {
			if (position < filteredModels.size()) selectModel(filteredModels.get(position));
		} else if (position < filteredItems.size()) {
			selectedItem = filteredItems.get(position);
			Identifier current = ItemModelOverrides.selectedModel(itemId(selectedItem));
			selectedModel = catalogSnapshot.stream().filter(model -> model.modelId().equals(current)).findFirst().orElse(null);
			status = "";
			downloadFailed = false;
			downloading = false;
			show(Page.MODELS);
			if (selectedModel != null) selectModel(selectedModel);
		}
	}

	private void selectModel(CatalogModel model) {
		selectedModel = model;
		downloadFailed = false;
		downloading = !CatalogService.isLoaded(model);
		status = "";
		CatalogService.load(model).whenComplete((unused, failure) -> minecraft.execute(() -> {
			if (!model.equals(selectedModel)) return;
			downloading = false;
			downloadFailed = failure != null;
			status = failure == null ? "Модель готова" : "Ошибка загрузки. Нажми Повторить.";
			updateRows();
		}));
	}

	private void applyOrRetry() {
		if (selectedItem == null || selectedModel == null) return;
		if (downloadFailed) {
			CatalogService.retry(selectedModel);
			selectModel(selectedModel);
			return;
		}
		if (!CatalogService.isLoaded(selectedModel)) return;
		try {
			ItemModelOverrides.setModel(itemId(selectedItem), selectedModel.modelId());
			status = "Модель применена";
		} catch (IOException failure) {
			status = "Не удалось сохранить выбор";
			LoggerFactory.getLogger("ModelMirage").error("Could not save model selection", failure);
		}
	}

	private void resetModel() {
		if (selectedItem == null) return;
		try {
			ItemModelOverrides.setModel(itemId(selectedItem), null);
			selectedModel = null;
			downloadFailed = false;
			downloading = false;
			status = "Обычная модель восстановлена";
		} catch (IOException failure) {
			status = "Не удалось сохранить выбор";
			LoggerFactory.getLogger("ModelMirage").error("Could not reset model selection", failure);
		}
	}

	private MouseButtonEvent local(MouseButtonEvent event) {
		return new MouseButtonEvent((event.x() - originX) / scale, (event.y() - originY) / scale, event.buttonInfo());
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		MouseButtonEvent mapped = local(event);
		if (mapped.button() == 0 && inPreview(mapped.x(), mapped.y())) {
			dragging = true;
			return true;
		}
		return super.mouseClicked(mapped, doubleClick);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = false;
		return super.mouseReleased(local(event));
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging) {
			yaw += (float) (dx / scale * 0.015);
			pitch = Math.clamp(pitch + (float) (dy / scale * 0.015), -1.3f, 1.3f);
			return true;
		}
		return super.mouseDragged(local(event), dx / scale, dy / scale);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		double mx = (x - originX) / scale;
		double my = (y - originY) / scale;
		if (inPreview(mx, my)) {
			zoom = Math.clamp(zoom + (float) vertical * 0.1f, 0.5f, 1.6f);
			return true;
		}
		if (mx >= 150 && mx <= 356 && my >= 58 && my <= 304) {
			int count = page == Page.MODELS ? filteredModels.size() : filteredItems.size();
			offset = Math.clamp(offset - (int) Math.signum(vertical) * 3, 0, Math.max(0, count - ROWS));
			updateRows();
			return true;
		}
		return super.mouseScrolled(mx, my, horizontal, vertical);
	}

	private static boolean inPreview(double x, double y) { return x >= 374 && x < 562 && y >= 87 && y < 223; }
	private static Identifier itemId(Item item) { return BuiltInRegistries.ITEM.getKey(item); }
	private static String itemName(Item item) { return Component.translatable(item.getDescriptionId()).getString(); }
	private enum Page { ITEMS, MODELS, APPLIED }
}
