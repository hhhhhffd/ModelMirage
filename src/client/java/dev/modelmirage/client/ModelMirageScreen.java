package dev.modelmirage.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.modelmirage.client.catalog.CatalogModel;
import dev.modelmirage.client.catalog.CatalogService;
import dev.modelmirage.client.gui.AstolfoButton;
import dev.modelmirage.client.gui.AstolfoUi;
import dev.modelmirage.client.gui.ModelCard;
import dev.modelmirage.client.gui.ModuleWidget;
import dev.modelmirage.client.gui.VisualSettingWidget;
import dev.modelmirage.client.render.RuntimeModels;
import dev.modelmirage.client.visual.VisualModule;
import dev.modelmirage.client.visual.Visuals;
import dev.modelmirage.client.visual.HudEditorScreen;
import dev.modelmirage.client.visual.AppearanceScreen;
import dev.modelmirage.client.visual.VisualPreferences;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.LoggerFactory;

/** Native Fabric screen with Neverlose's grouped navigation and inventory workflow. */
public final class ModelMirageScreen extends Screen {
	private static final int W = 650;
	private static final int H = 410;
	private static final int SIDE = 140;
	private static final int TOP = 86;
	private static final int BOTTOM = 376;
	private final List<Item> allItems = BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR)
			.sorted((a, b) -> BuiltInRegistries.ITEM.getKey(a).compareTo(BuiltInRegistries.ITEM.getKey(b))).toList();
	private final List<AbstractWidget> chrome = new ArrayList<>();
	private final List<Placed> content = new ArrayList<>();
	private final List<Nav> navigation = new ArrayList<>();
	private List<CatalogModel> catalog = List.of();
	private EditBox search;
	private AstolfoButton itemPicker;
	private AstolfoButton apply;
	private AstolfoButton reset;
	private AstolfoButton back;
	private AstolfoButton bind;
	private AstolfoButton hudEditor;
	private Page page = Page.INVENTORY;
	private VisualModule selectedModule;
	private Item selectedItem;
	private CatalogModel selectedModel;
	private boolean capturingBind;
	private boolean pickingItem;
	private boolean downloading;
	private boolean failed;
	private boolean dragging;
	private boolean dirty;
	private int saveDelay;
	private String status = "";
	private float scale;
	private float originX;
	private float originY;
	private float opening;
	private float tabY = 87;
	private float transition;
	private float scroll;
	private float targetScroll;
	private float scrollMaximum;
	private float yaw;
	private float pitch;
	private float zoom = 1;
	private long frame = System.nanoTime();

	public ModelMirageScreen() { super(Component.literal("ModelMirage")); }

	@Override
	protected void init() {
		chrome.clear();
		content.clear();
		navigation.clear();
		String query = search == null ? "" : search.getValue();
		search = new EditBox(font, 459, 18, 149, 16, Component.literal("Поиск"));
		search.setBordered(false);
		search.setMaxLength(128);
		search.setHint(Component.literal("Поиск..."));
		search.setValue(query);
		search.setResponder(value -> { targetScroll = scroll = 0; rebuild(); });
		chrome.add(addRenderableWidget(search));
		button(155, 11, 78, 29, "Сохранить", false, this::save).compact = true;
		button(245, 11, 148, 29, "Оформление", false, () -> minecraft.gui.setScreen(new AppearanceScreen(this))).compact = true;
		button(620, 12, 21, 27, "x", false, this::onClose).compact = true;
		nav(Page.INVENTORY, 87, "Модели", "E");
		nav(Page.APPLIED, 122, "Применённые", "H");
		nav(Page.HUD, 192, "HUD", "K");
		button(14, 330, 112, 27, "Обновить каталог", false, () -> CatalogService.refresh()).compact = true;
		back = button(154, 54, 28, 25, "<", false, () -> { selectedModule = null; pickingItem = false; clearSearch(); rebuild(); });
		itemPicker = button(191, 54, 206, 25, "Выбрать предмет", false, () -> { pickingItem = !pickingItem; clearSearch(); rebuild(); });
		itemPicker.compact = true;
		bind = button(503, 54, 131, 25, "Назначить клавишу", false, () -> capturingBind = true);
		bind.compact = true;
		hudEditor = button(503, 54, 131, 25, "Редактор HUD", false, () -> { save(); if (!dirty) minecraft.gui.setScreen(new HudEditorScreen(this)); });
		hudEditor.compact = true;
		apply = button(433, 327, 126, 31, "Применить", true, this::applyOrRetry);
		reset = button(565, 327, 68, 31, "Сброс", false, this::resetModel);
		button(547, 281, 86, 22, "Сбросить вид", false, () -> { yaw = pitch = 0; zoom = 1; }).compact = true;
		catalog = CatalogService.models();
		if (selectedItem == null && minecraft.player != null && !minecraft.player.getMainHandItem().isEmpty()) {
			selectedItem = minecraft.player.getMainHandItem().getItem();
			restoreSelectedModel();
		}
		pickingItem = page == Page.INVENTORY && selectedItem == null;
		rebuild();
	}

	private AstolfoButton button(int x, int y, int width, int height, String text, boolean primary, Runnable action) {
		AstolfoButton button = addRenderableWidget(new AstolfoButton(x, y, width, height, text, primary, action));
		chrome.add(button);
		return button;
	}

	private void nav(Page page, int y, String name, String icon) {
		AstolfoButton button = button(10, y, 120, 30, name, false, () -> show(page));
		button.symbol = icon;
		button.navigation = true;
		navigation.add(new Nav(page, y, button));
	}

	private void show(Page next) {
		page = next;
		selectedModule = null;
		capturingBind = false;
		pickingItem = page == Page.INVENTORY && selectedItem == null;
		transition = 0;
		clearSearch();
		rebuild();
	}

	private void clearSearch() {
		targetScroll = scroll = 0;
		search.setValue("");
	}

	private void addContent(AbstractWidget widget, int y) {
		addRenderableWidget(widget);
		content.add(new Placed(widget, y));
	}

	private void rebuild() {
		String focusedSetting = getFocused() instanceof VisualSettingWidget focused ? focused.setting.id : null;
		for (Placed row : content) removeWidget(row.widget());
		content.clear();
		String query = search.getValue().trim().toLowerCase(Locale.ROOT);
		int end = TOP;
		if (isVisuals()) {
			if (selectedModule == null) {
				List<VisualModule> modules = Visuals.modules().stream().filter(module -> module.category.equals(page.category))
						.filter(module -> (module.id + " " + module.name).toLowerCase(Locale.ROOT).contains(query)).toList();
				for (int index = 0; index < modules.size(); index++) {
					VisualModule module = modules.get(index);
					int x = 155 + index % 2 * 245;
					int y = TOP + index / 2 * 42;
					addContent(new ModuleWidget(x, y, 236, module, () -> openModule(module), this::changed), y);
					end = y + 42;
				}
			} else {
				List<VisualModule.Setting> settings = selectedModule.settings.values().stream()
						.filter(setting -> setting.name.toLowerCase(Locale.ROOT).contains(query)).toList();
				for (int index = 0; index < settings.size(); index++) {
					int x = 155 + index % 2 * 245;
					int y = TOP + 47 + index / 2 * 49;
					addContent(new VisualSettingWidget(x, y, 236, settings.get(index), this::changed), y);
					end = y + 49;
				}
				AstolfoButton toggle = new AstolfoButton(155, TOP, 481, 34,
						selectedModule.enabled ? "Включено" : "Включить", false, () -> { selectedModule.enabled = !selectedModule.enabled; changed(); rebuild(); });
				toggle.selected = selectedModule.enabled;
				addContent(toggle, TOP);
				end = Math.max(end, TOP + 44);
			}
		} else if (pickingItem || page == Page.APPLIED) {
			List<Item> items = allItems.stream().filter(item -> page != Page.APPLIED || ItemModelOverrides.isEnabled(itemId(item)))
					.filter(item -> itemId(item).toString().contains(query) || itemName(item).toLowerCase(Locale.ROOT).contains(query)).toList();
			for (int index = 0; index < items.size(); index++) {
				Item item = items.get(index);
				int x = 155 + index % 2 * 245;
				int y = TOP + index / 2 * 45;
				AstolfoButton row = new AstolfoButton(x, y, 236, 40, itemName(item), false, () -> selectItem(item));
				row.item = new ItemStack(item);
				row.subtitle = itemId(item).toString();
				addContent(row, y);
				end = y + 45;
			}
		} else {
			List<CatalogModel> models = catalog.stream().filter(model -> model.name().toLowerCase(Locale.ROOT).contains(query) || model.id().contains(query)).toList();
			for (int index = 0; index < models.size(); index++) {
				CatalogModel model = models.get(index);
				int x = 155 + index % 2 * 133;
				int y = TOP + index / 2 * 134;
				ModelCard card = new ModelCard(x, y, model, () -> selectModel(model));
				card.selected = model.equals(selectedModel);
				card.applied = selectedItem != null && model.modelId().equals(ItemModelOverrides.selectedModel(itemId(selectedItem)));
				addContent(card, y);
				end = y + 134;
			}
		}
		scrollMaximum = Math.max(0, end - BOTTOM);
		targetScroll = Math.clamp(targetScroll, 0, scrollMaximum);
		if (focusedSetting != null) {
			content.stream().map(Placed::widget).filter(widget -> widget instanceof VisualSettingWidget control && control.setting.id.equals(focusedSetting))
					.findFirst().ifPresent(this::setFocused);
		}
	}

	private void openModule(VisualModule module) {
		selectedModule = module;
		transition = 0;
		clearSearch();
		rebuild();
	}

	private void changed() { dirty = true; saveDelay = 10; }

	private void save() {
		try {
			Visuals.save();
			dirty = false;
			status = "Настройки сохранены";
		} catch (IOException failure) {
			saveDelay = 20;
			status = "Не удалось сохранить настройки";
			LoggerFactory.getLogger("ModelMirage").error("Could not save visual settings", failure);
		}
	}

	@Override
	public void tick() {
		if (dirty && --saveDelay <= 0) save();
		if (catalog != CatalogService.models()) {
			catalog = CatalogService.models();
			if (selectedModel == null && selectedItem != null) restoreSelectedModel();
			else if (selectedModel != null) {
				CatalogModel updated = catalog.stream().filter(model -> model.id().equals(selectedModel.id())).findFirst().orElse(null);
				if (updated == null) { selectedModel = null; downloading = failed = false; }
				else if (!updated.equals(selectedModel)) selectModel(updated);
			}
			rebuild();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		long now = System.nanoTime();
		float seconds = Math.min((now - frame) / 1e9f, 0.1f);
		frame = now;
		opening = VisualPreferences.animations ? AstolfoUi.animate(opening, 1, 15, seconds) : 1;
		transition = VisualPreferences.animations ? AstolfoUi.animate(transition, 1, 20, seconds) : 1;
		scroll = VisualPreferences.animations ? AstolfoUi.animate(scroll, targetScroll, 18, seconds) : targetScroll;
		scale = Math.min(VisualPreferences.guiScale, Math.min((width - 16f) / W, (height - 16f) / H)) * (0.96f + opening * 0.04f);
		originX = (width - W * scale) / 2;
		originY = (height - H * scale) / 2;
		int mx = (int) ((mouseX - originX) / scale);
		int my = (int) ((mouseY - originY) / scale);
		graphics.fill(0, 0, width, height, Math.round(120 * VisualPreferences.opacity) << 24 | 0x02070D);
		graphics.pose().pushMatrix();
		graphics.pose().translate(originX, originY);
		graphics.pose().scale(scale, scale);
		for (int i = 6; i > 0; i--) AstolfoUi.rounded(graphics, -i * 2, -i * 2, W + i * 4, H + i * 4, 8 + i * 2, 0x12000000);
		AstolfoUi.rounded(graphics, 0, 0, W, H, 8, AstolfoUi.BACKGROUND);
		AstolfoUi.shape(graphics, 0, 0, SIDE, H, 8, 0, AstolfoUi.SIDEBAR, 0xFF080E17);
		graphics.fill(SIDE - 8, 0, SIDE, H, AstolfoUi.SIDEBAR);
		AstolfoUi.border(graphics, 0, 0, W, H, 8, AstolfoUi.LINE);
		graphics.fill(SIDE, 0, SIDE + 1, H, AstolfoUi.LINE);
		graphics.fill(SIDE, 48, W, 49, AstolfoUi.LINE);
		AstolfoUi.BOLD.draw(graphics, "MODELMIRAGE", 13, 19, 15, AstolfoUi.TEXT);
		AstolfoUi.MEDIUM.draw(graphics, "БИБЛИОТЕКА", 17, 65, 8, AstolfoUi.MUTED);
		AstolfoUi.MEDIUM.draw(graphics, "ОФОРМЛЕНИЕ", 17, 170, 8, AstolfoUi.MUTED);
		for (Nav nav : navigation) {
			nav.button().selected = nav.page() == page;
			if (nav.page() == page) tabY = VisualPreferences.animations ? AstolfoUi.animate(tabY, nav.y(), 19, seconds) : nav.y();
		}
		AstolfoUi.shape(graphics, 10, tabY, 120, 30, 5, 0, 0x4422759B, 0x1822759B);
		AstolfoUi.shape(graphics, 11, tabY + 8, 2, 14, 1, 0, AstolfoUi.ACCENT, AstolfoUi.ACCENT);
		AstolfoUi.rounded(graphics, 433, 11, 180, 29, 5, AstolfoUi.PANEL);
		AstolfoUi.border(graphics, 433, 11, 180, 29, 5, search.isFocused() ? AstolfoUi.ACCENT : AstolfoUi.LINE);
		AstolfoUi.ICONS.draw(graphics, "I", 442, 19, 12, AstolfoUi.MUTED);
		AstolfoUi.SEMIBOLD.draw(graphics, minecraft.player == null ? "ModelMirage" : minecraft.player.getName().getString(), 16, 367, 10, AstolfoUi.TEXT);
		AstolfoUi.MEDIUM.draw(graphics, CatalogService.loading() ? "Загрузка каталога..." : CatalogService.cached() ? "Каталог из кеша" : CatalogService.error().isEmpty() ? "Каталог подключён" : "Каталог недоступен", 16, 384, 9, AstolfoUi.MUTED);
		back.visible = selectedModule != null || pickingItem;
		itemPicker.visible = !isVisuals() && page != Page.APPLIED;
		itemPicker.setMessage(Component.literal(selectedItem == null ? "Выбрать предмет" : itemName(selectedItem) + "  v"));
		bind.visible = selectedModule != null;
		hudEditor.visible = page == Page.HUD && selectedModule == null;
		bind.setMessage(Component.literal(capturingBind ? "Нажми клавишу..." : selectedModule == null || selectedModule.bind < 0 ? "Назначить клавишу" : "Key " + selectedModule.bind));
		String heading = selectedModule != null ? moduleName(selectedModule) : page.label;
		if (!itemPicker.visible) AstolfoUi.SEMIBOLD.draw(graphics, heading, back.visible ? 190 : 156, 61, 13, AstolfoUi.TEXT);
		if (itemPicker.visible) {
			AstolfoUi.MEDIUM.draw(graphics, "Предмет", 155, 63, 10, AstolfoUi.MUTED);
			back.visible = false;
		}
		boolean preview = page == Page.INVENTORY && !pickingItem;
		apply.visible = reset.visible = preview;
		apply.active = selectedModel != null && !downloading && (failed || CatalogService.isLoaded(selectedModel));
		reset.active = selectedItem != null && ItemModelOverrides.isEnabled(itemId(selectedItem));
		apply.setMessage(Component.literal(failed ? "Повторить" : downloading ? "Загрузка..." : isApplied() ? "Применено" : "Применить"));
		if (preview) drawPreview(graphics);
		for (AbstractWidget widget : chrome) {
			if (widget.getY() == 281) widget.visible = preview;
			widget.extractRenderState(graphics, mx, my, delta);
		}
		graphics.enableScissor(151, TOP, preview ? 417 : 638, BOTTOM);
		if (isVisuals()) {
			int panelY = selectedModule == null ? TOP : TOP + 46;
			for (int column = 0; column < 2; column++) {
				AstolfoUi.rounded(graphics, 155 + column * 245, panelY, 236, BOTTOM - panelY, 6, AstolfoUi.PANEL);
				AstolfoUi.border(graphics, 155 + column * 245, panelY, 236, BOTTOM - panelY, 6, AstolfoUi.LINE);
			}
		}
		for (Placed row : content) {
			AbstractWidget widget = row.widget();
			widget.setY(Math.round(row.y() - scroll + (1 - transition) * 5));
			widget.visible = widget.getY() < BOTTOM && widget.getY() + widget.getHeight() > TOP;
			widget.extractRenderState(graphics, my >= TOP && my < BOTTOM ? mx : -1000, my, delta);
		}
		graphics.disableScissor();
		if (content.isEmpty()) {
			AstolfoUi.SEMIBOLD.draw(graphics, CatalogService.loading() && !isVisuals() ? "Загрузка каталога..." : "Ничего не найдено", 170, 116, 13, AstolfoUi.TEXT);
			AstolfoUi.MEDIUM.draw(graphics, selectedModule != null ? "У этого модуля нет дополнительных настроек" : "Попробуй другой поиск", 170, 138, 10, AstolfoUi.MUTED);
		}
		if (scrollMaximum > 0) {
			int x = preview ? 412 : 639;
			int track = BOTTOM - TOP;
			int thumb = Math.max(20, Math.round(track * track / (scrollMaximum + track)));
			AstolfoUi.rounded(graphics, x, TOP, 2, track, 1, AstolfoUi.LINE);
			AstolfoUi.rounded(graphics, x, TOP + Math.round(scroll / scrollMaximum * (track - thumb)), 2, thumb, 1, AstolfoUi.ACCENT);
		}
		graphics.fill(SIDE, 382, W, 383, AstolfoUi.LINE);
		AstolfoUi.MEDIUM.draw(graphics, AstolfoUi.MEDIUM.fit(status.isEmpty() ? "Right Shift: меню" : status, 9, 360), 155, 394, 9, failed ? 0xFFFF7C8F : AstolfoUi.MUTED);
		AstolfoUi.MEDIUM.draw(graphics, "Локальные изменения", 527, 394, 9, AstolfoUi.MUTED);
		graphics.pose().popMatrix();
	}

	private void drawPreview(GuiGraphicsExtractor graphics) {
		AstolfoUi.shape(graphics, 425, TOP, 212, 222, 6, 0, AstolfoUi.PANEL, 0xFF080F1A);
		AstolfoUi.border(graphics, 425, TOP, 212, 222, 6, AstolfoUi.LINE);
		AstolfoUi.MEDIUM.draw(graphics, "ПРЕДПРОСМОТР", 438, 99, 8, AstolfoUi.MUTED);
		graphics.enableScissor(431, 116, 631, 260);
		if (selectedModel != null && CatalogService.isLoaded(selectedModel)) {
			RuntimeModels.preview(graphics, selectedModel.modelId(), 531, 186, 118 * zoom, yaw, pitch);
		} else {
			AstolfoUi.ICONS.draw(graphics, "E", 509, 155, 36, AstolfoUi.MUTED);
			String text = failed ? "Ошибка загрузки" : downloading ? "Загрузка модели..." : "Выбери модель";
			AstolfoUi.MEDIUM.draw(graphics, text, 531 - AstolfoUi.MEDIUM.width(text, 11) / 2, 213, 11, AstolfoUi.MUTED);
		}
		graphics.disableScissor();
		AstolfoUi.SEMIBOLD.draw(graphics, AstolfoUi.SEMIBOLD.fit(selectedModel == null ? "Модель не выбрана" : selectedModel.name(), 11, 178), 438, 265, 11, AstolfoUi.TEXT);
		AstolfoUi.MEDIUM.draw(graphics, "Тяни / колесо", 438, 288, 8, AstolfoUi.MUTED);
		AstolfoUi.MEDIUM.draw(graphics, isApplied() ? "Применена ко всем экземплярам" : "Для выбранного предмета", 434, 365, 9, isApplied() ? AstolfoUi.ACCENT : AstolfoUi.MUTED);
	}

	private void selectItem(Item item) {
		selectedItem = item;
		page = Page.INVENTORY;
		pickingItem = false;
		status = "";
		restoreSelectedModel();
		clearSearch();
		rebuild();
	}

	private void restoreSelectedModel() {
		Identifier current = ItemModelOverrides.selectedModel(itemId(selectedItem));
		selectedModel = catalog.stream().filter(model -> model.modelId().equals(current)).findFirst().orElse(null);
		failed = downloading = false;
		if (selectedModel != null) selectModel(selectedModel);
	}

	private void selectModel(CatalogModel model) {
		selectedModel = model;
		failed = false;
		downloading = !CatalogService.isLoaded(model);
		status = "";
		CatalogService.load(model).whenComplete((unused, failure) -> minecraft.execute(() -> {
			if (!model.equals(selectedModel)) return;
			downloading = false;
			failed = failure != null;
			status = failed ? "Не удалось загрузить модель. Нажми Повторить." : "Модель готова к применению";
			rebuild();
		}));
		rebuild();
	}

	private boolean isApplied() {
		return selectedItem != null && selectedModel != null && selectedModel.modelId().equals(ItemModelOverrides.selectedModel(itemId(selectedItem)));
	}

	private void applyOrRetry() {
		if (selectedModel == null || selectedItem == null) return;
		if (failed) { CatalogService.retry(selectedModel); selectModel(selectedModel); return; }
		if (!CatalogService.isLoaded(selectedModel)) return;
		try {
			ItemModelOverrides.setModel(itemId(selectedItem), selectedModel.modelId());
			status = "Модель применена";
			rebuild();
		} catch (IOException failure) {
			status = "Не удалось сохранить выбор";
			LoggerFactory.getLogger("ModelMirage").error("Could not apply item model", failure);
		}
	}

	private void resetModel() {
		if (selectedItem == null) return;
		try {
			ItemModelOverrides.setModel(itemId(selectedItem), null);
			status = "Обычная модель восстановлена";
			rebuild();
		} catch (IOException failure) {
			status = "Не удалось сохранить выбор";
			LoggerFactory.getLogger("ModelMirage").error("Could not reset item model", failure);
		}
	}

	@Override
	public void onClose() {
		if (dirty) { save(); if (dirty) return; }
		super.onClose();
	}

	private MouseButtonEvent local(MouseButtonEvent event) {
		return new MouseButtonEvent((event.x() - originX) / scale, (event.y() - originY) / scale, event.buttonInfo());
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		MouseButtonEvent mapped = local(event);
		if (page == Page.INVENTORY && !pickingItem && mapped.button() == 0 && inPreview(mapped.x(), mapped.y())) {
			dragging = true;
			return true;
		}
		if (mapped.y() < TOP || mapped.y() >= BOTTOM) {
			for (Placed row : content) row.widget().active = false;
			boolean handled = super.mouseClicked(mapped, doubleClick);
			for (Placed row : content) row.widget().active = true;
			return handled;
		}
		return super.mouseClicked(mapped, doubleClick);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) { dragging = false; return super.mouseReleased(local(event)); }

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
		if (page == Page.INVENTORY && !pickingItem && inPreview(mx, my)) {
			zoom = Math.clamp(zoom + (float) vertical * 0.1f, 0.5f, 1.6f);
			return true;
		}
		if (mx >= 151 && mx <= 641 && my >= TOP && my < BOTTOM) {
			targetScroll = Math.clamp(targetScroll - (float) vertical * 42, 0, scrollMaximum);
			return true;
		}
		return super.mouseScrolled(mx, my, horizontal, vertical);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (capturingBind) {
			if (event.key() != InputConstants.KEY_ESCAPE) {
				selectedModule.bind = event.key() == InputConstants.KEY_BACKSPACE ? -1 : event.key();
				changed();
			}
			capturingBind = false;
			return true;
		}
		return super.keyPressed(event);
	}

	private boolean isVisuals() { return page.category != null; }
	private static boolean inPreview(double x, double y) { return x >= 431 && x < 631 && y >= 116 && y < 260; }
	private static Identifier itemId(Item item) { return BuiltInRegistries.ITEM.getKey(item); }
	private static String itemName(Item item) { return Component.translatable(item.getDescriptionId()).getString(); }
	private static String moduleName(VisualModule module) { return module.name; }
	private record Placed(AbstractWidget widget, int y) { }
	private record Nav(Page page, int y, AstolfoButton button) { }
	private enum Page {
		HUD("HUD", "hud"),
		INVENTORY("Модели предметов", null), APPLIED("Применённые модели", null);
		final String label;
		final String category;
		Page(String label, String category) { this.label = label; this.category = category; }
	}
}
