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

/** Astolfo ClickGui layout with native model selection and HUD controls. */
public final class ModelMirageScreen extends Screen {
	private static final int W = 580;
	private static final int H = 380;
	private static final int SIDE = 138;
	private static final int TOP = 68;
	private static final int BOTTOM = 348;
	private final List<Item> allItems = BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR)
			.sorted((a, b) -> BuiltInRegistries.ITEM.getKey(a).compareTo(BuiltInRegistries.ITEM.getKey(b))).toList();
	private final List<AbstractWidget> chrome = new ArrayList<>();
	private final List<Placed> content = new ArrayList<>();
	private final List<Nav> navigation = new ArrayList<>();
	private final List<ModuleGroup> groups = new ArrayList<>();
	private boolean closing;
	private float searchFocus;
	private List<CatalogModel> catalog = List.of();
	private EditBox search;
	private AstolfoButton itemPicker;
	private AstolfoButton apply;
	private AstolfoButton reset;
	private AstolfoButton hudEditor;
	private Page page = Page.HUD;
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
	private float tabY = 70;
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
	public boolean isPauseScreen() { return false; }
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) { }

	@Override
	protected void init() {
		chrome.clear();
		content.clear();
		navigation.clear();
		String query = search == null ? "" : search.getValue();
		search = new EditBox(font, 440, 13, 115, 14, Component.literal("Поиск"));
		search.setBordered(false);
		search.setMaxLength(128);
		search.setHint(Component.literal("Поиск..."));
		search.setValue(query);
		search.setResponder(value -> { targetScroll = scroll = 0; rebuild(); });
		chrome.add(addRenderableWidget(search));
		button(560, 10, 14, 20, "x", false, this::onClose).compact = true;
		nav(Page.HUD, 70, "Render", "F");
		nav(Page.INVENTORY, 127, "Модели", "E");
		nav(Page.APPLIED, 154, "Применённые", "H");
		button(13, 310, 112, 22, "Обновить каталог", false, () -> CatalogService.refresh()).compact = true;
		button(113, 348, 16, 18, "", false, () -> minecraft.gui.setScreen(new AppearanceScreen(this))).symbol = "J";
		itemPicker = button(150, 40, 218, 22, "Выбрать предмет", false, () -> { pickingItem = !pickingItem; clearSearch(); rebuild(); });
		itemPicker.compact = true;
		hudEditor = button(437, 40, 130, 22, "Редактор HUD", false, () -> { save(); if (!dirty) minecraft.gui.setScreen(new HudEditorScreen(this)); });
		hudEditor.compact = true;
		apply = button(374, 298, 118, 29, "Применить", true, this::applyOrRetry);
		reset = button(498, 298, 68, 29, "Сброс", false, this::resetModel);
		button(484, 260, 82, 20, "Сбросить вид", false, () -> { yaw = pitch = 0; zoom = 1; }).compact = true;
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
		AstolfoButton button = button(8, y, 122, 22, name, false, () -> show(page));
		button.symbol = icon;
		button.navigation = true;
		navigation.add(new Nav(page, y, button));
	}

	private void show(Page next) {
		page = next;
		selectedModule = null;
		capturingBind = false;
		status = "";
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
		groups.clear();
		String query = search.getValue().trim().toLowerCase(Locale.ROOT);
		int end = TOP;
		if (isVisuals()) {
			List<VisualModule> modules = Visuals.modules().stream().filter(module -> module.category.equals(page.category))
					.filter(module -> (module.id + " " + module.name + " " + module.settings.values().stream().map(setting -> setting.name).collect(java.util.stream.Collectors.joining(" "))).toLowerCase(Locale.ROOT).contains(query)).toList();
			int[] columnY = {TOP, TOP};
			for (VisualModule module : modules) {
				int column = columnY[0] <= columnY[1] ? 0 : 1;
				int x = 150 + column * 214;
				int y = columnY[column];
				ModuleWidget header = new ModuleWidget(x, y, 205, module, () -> openModule(module), this::changed);
				addContent(header, y);
				int nextY = y + 24;
				for (var setting : module.settings.values()) {
					VisualSettingWidget control = new VisualSettingWidget(x, nextY, 205, setting, this::changed);
					addContent(control, nextY);
					nextY += control.getHeight();
				}
				groups.add(new ModuleGroup(header, y, nextY - y + 4));
				columnY[column] = nextY + 12;
			}
			end = Math.max(columnY[0], columnY[1]);
		} else if (pickingItem || page == Page.APPLIED) {
			List<Item> items = allItems.stream().filter(item -> page != Page.APPLIED || ItemModelOverrides.isEnabled(itemId(item)))
					.filter(item -> itemId(item).toString().contains(query) || itemName(item).toLowerCase(Locale.ROOT).contains(query)).toList();
			for (int index = 0; index < items.size(); index++) {
				Item item = items.get(index);
				int x = 150 + index % 2 * 214;
				int y = TOP + index / 2 * 45;
				AstolfoButton row = new AstolfoButton(x, y, 205, 40, itemName(item), false, () -> selectItem(item));
				row.item = new ItemStack(item);
				row.subtitle = itemId(item).toString();
				addContent(row, y);
				end = y + 45;
			}
		} else {
			List<CatalogModel> models = catalog.stream().filter(model -> model.name().toLowerCase(Locale.ROOT).contains(query) || model.id().contains(query)).toList();
			for (int index = 0; index < models.size(); index++) {
				CatalogModel model = models.get(index);
				int x = 150 + index % 2 * 106;
				int y = TOP + index / 2 * 123;
				ModelCard card = new ModelCard(x, y, model, () -> selectModel(model));
				card.selected = model.equals(selectedModel);
				card.applied = selectedItem != null && model.modelId().equals(ItemModelOverrides.selectedModel(itemId(selectedItem)));
				addContent(card, y);
				end = y + 123;
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
		capturingBind = true;
		status = "Нажми клавишу для " + module.id + ". Backspace: удалить / Esc: отменить";
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
		opening = VisualPreferences.animations ? AstolfoUi.animate(opening, closing ? 0 : 1, 14, seconds) : 1;
		transition = VisualPreferences.animations ? AstolfoUi.animate(transition, 1, 20, seconds) : 1;
		scroll = VisualPreferences.animations ? AstolfoUi.animate(scroll, targetScroll, 18, seconds) : targetScroll;
		if (closing && opening < 0.005f) { super.onClose(); return; }
		searchFocus = AstolfoUi.animate(searchFocus, search.isFocused() ? 1 : 0, 16, seconds);
		scale = Math.min((float) (2f / minecraft.getWindow().getGuiScale()) * VisualPreferences.guiScale, Math.min((width - 16f) / W, (height - 16f) / H)) * (0.92f + opening * 0.08f);
		originX = (width - W * scale) / 2;
		originY = (height - H * scale) / 2;
		int mx = (int) ((mouseX - originX) / scale);
		int my = (int) ((mouseY - originY) / scale);
		graphics.fill(0, 0, width, height, Math.round(120 * VisualPreferences.opacity * opening) << 24 | 0x02070D);
		graphics.pose().pushMatrix();
		graphics.pose().translate(originX, originY);
		graphics.pose().scale(scale, scale);
		AstolfoUi.alpha = opening;
		AstolfoUi.shadow(graphics, 0, 0, W, H, 10);
		AstolfoUi.rounded(graphics, 0, 0, W, H, 10, AstolfoUi.BACKGROUND);
		graphics.fill(SIDE, 0, SIDE + 1, H, AstolfoUi.opacity(AstolfoUi.LINE, opening));
		graphics.fill(SIDE, 36, W, 37, AstolfoUi.opacity(AstolfoUi.LINE, opening));
		AstolfoUi.glow(graphics, SIDE / 2f, 20, 6, AstolfoUi.ACCENT, 0.08f);
		AstolfoUi.LOGO.draw(graphics, "A", (SIDE - 33) / 2f, 4, 33, AstolfoUi.ACCENT);
		graphics.fill(12, 48, 126, 49, AstolfoUi.opacity(AstolfoUi.LINE, opening));
		AstolfoUi.MEDIUM.draw(graphics, "Functions", 14, 58, 8, 0xFF6E6E7D);
		AstolfoUi.MEDIUM.draw(graphics, "Library", 14, 115, 8, 0xFF6E6E7D);
		for (Nav nav : navigation) {
			nav.button().selected = nav.page() == page;
			if (nav.page() == page) tabY = AstolfoUi.animate(tabY, nav.y(), 18, seconds);
		}
		AstolfoUi.shape(graphics, 8, tabY, 122, 22, 5, 0, AstolfoUi.mix(0xFF000000, AstolfoUi.ACCENT, 0.22f), AstolfoUi.mix(0xFF000000, AstolfoUi.ACCENT, 0.22f));
		graphics.fill(21, 95, 22, 106, AstolfoUi.opacity(0x99555569, opening));
		graphics.fill(21, 103, 30, 104, AstolfoUi.opacity(0x99555569, opening));
		AstolfoUi.MEDIUM.draw(graphics, "Interface", 38, 100, 7.8f, page == Page.HUD ? AstolfoUi.ACCENT : AstolfoUi.MUTED);
		AstolfoUi.MEDIUM.draw(graphics, Integer.toString(Visuals.modules().size()), 115, 101, 6.5f, AstolfoUi.ACCENT);
		AstolfoUi.rounded(graphics, 419, 8, 139, 21, 5, AstolfoUi.mix(AstolfoUi.PANEL, 0xFF101018, searchFocus));
		AstolfoUi.border(graphics, 419, 8, 139, 21, 5, AstolfoUi.mix(AstolfoUi.LINE, AstolfoUi.ACCENT, searchFocus));
		AstolfoUi.ICONS.draw(graphics, "I", 425, 14, 9, AstolfoUi.MUTED);
		AstolfoUi.SEMIBOLD.draw(graphics, "Astolfo Visuals", 150, 13, 10, AstolfoUi.TEXT);
		graphics.fill(12, 336, 126, 337, AstolfoUi.opacity(AstolfoUi.LINE, opening));
		AstolfoUi.SEMIBOLD.draw(graphics, minecraft.player == null ? "ModelMirage" : AstolfoUi.SEMIBOLD.fit(minecraft.player.getName().getString(), 9, 91), 14, 348, 9, AstolfoUi.TEXT);
		AstolfoUi.MEDIUM.draw(graphics, CatalogService.loading() ? "Загрузка..." : CatalogService.error().isEmpty() ? "ModelMirage" : "Каталог недоступен", 14, 362, 7, AstolfoUi.MUTED);
		itemPicker.visible = !isVisuals() && page != Page.APPLIED;
		itemPicker.setMessage(Component.literal(selectedItem == null ? "Выбрать предмет" : itemName(selectedItem) + "  v"));
		hudEditor.visible = page == Page.HUD;
		if (!itemPicker.visible) AstolfoUi.SEMIBOLD.draw(graphics, page.label, 150, 47, 10, AstolfoUi.TEXT);
		boolean preview = page == Page.INVENTORY && !pickingItem;
		apply.visible = reset.visible = preview;
		apply.active = selectedModel != null && !downloading && (failed || CatalogService.isLoaded(selectedModel));
		reset.active = selectedItem != null && ItemModelOverrides.isEnabled(itemId(selectedItem));
		apply.setMessage(Component.literal(failed ? "Повторить" : downloading ? "Загрузка..." : isApplied() ? "Применено" : "Применить"));
		if (preview) drawPreview(graphics);
		for (AbstractWidget widget : chrome) {
			if (widget.getY() == 260) widget.visible = preview;
			widget.extractRenderState(graphics, mx, my, delta);
		}
		graphics.enableScissor(146, TOP, preview ? 358 : 570, BOTTOM);
		for (ModuleGroup group : groups) {
			int y = Math.round(group.y() - scroll + (1 - transition) * 5);
			AstolfoUi.rounded(graphics, group.header().getX(), y, 205, group.height(), 7, AstolfoUi.PANEL);
		}
		for (Placed row : content) {
			AbstractWidget widget = row.widget();
			widget.setY(Math.round(row.y() - scroll + (1 - transition) * 5));
			widget.visible = widget.getY() < BOTTOM && widget.getY() + widget.getHeight() > TOP;
			widget.extractRenderState(graphics, my >= TOP && my < BOTTOM ? mx : -1000, my, delta);
		}
		graphics.disableScissor();
		if (content.isEmpty()) {
			AstolfoUi.SEMIBOLD.draw(graphics, CatalogService.loading() && !isVisuals() ? "Загрузка каталога..." : "Ничего не найдено", 160, 98, 13, AstolfoUi.TEXT);
			AstolfoUi.MEDIUM.draw(graphics, selectedModule != null ? "Нажми ПКМ на модуль для назначения клавиши" : "Попробуй другой поиск", 160, 120, 10, AstolfoUi.MUTED);
		}
		if (scrollMaximum > 0) {
			int x = preview ? 358 : 570;
			int track = BOTTOM - TOP;
			int thumb = Math.max(20, Math.round(track * track / (scrollMaximum + track)));
			AstolfoUi.rounded(graphics, x, TOP, 2, track, 1, AstolfoUi.LINE);
			AstolfoUi.rounded(graphics, x, TOP + Math.round(scroll / scrollMaximum * (track - thumb)), 2, thumb, 1, AstolfoUi.ACCENT);
		}
		graphics.fill(SIDE, 352, W, 353, AstolfoUi.opacity(AstolfoUi.LINE, opening));
		String footer = capturingBind ? "Клавиша для " + selectedModule.id + ": нажми клавишу / Backspace: удалить / Esc: отменить" : status.isEmpty() ? "ПКМ по модулю: клавиша / Right Shift: меню" : status;
		AstolfoUi.MEDIUM.draw(graphics, AstolfoUi.MEDIUM.fit(footer, 8, 411), 150, 365, 8, failed ? 0xFFFF7C8F : AstolfoUi.MUTED);
		AstolfoUi.alpha = 1;
		graphics.pose().popMatrix();
	}

	private void drawPreview(GuiGraphicsExtractor graphics) {
		AstolfoUi.rounded(graphics, 366, TOP, 202, 218, 7, AstolfoUi.PANEL);
		AstolfoUi.MEDIUM.draw(graphics, "Предпросмотр", 378, 79, 8, AstolfoUi.MUTED);
		graphics.enableScissor(372, 99, 562, 238);
		if (selectedModel != null && CatalogService.isLoaded(selectedModel)) {
			RuntimeModels.preview(graphics, selectedModel.modelId(), 467, 170, 112 * zoom, yaw, pitch);
		} else {
			AstolfoUi.ICONS.draw(graphics, "E", 453, 135, 29, AstolfoUi.MUTED);
			String text = failed ? "Ошибка загрузки" : downloading ? "Загрузка модели..." : "Выбери модель";
			AstolfoUi.MEDIUM.draw(graphics, text, 467 - AstolfoUi.MEDIUM.width(text, 9) / 2, 198, 9, AstolfoUi.MUTED);
		}
		graphics.disableScissor();
		AstolfoUi.SEMIBOLD.draw(graphics, AstolfoUi.SEMIBOLD.fit(selectedModel == null ? "Модель не выбрана" : selectedModel.name(), 10, 178), 378, 244, 10, AstolfoUi.TEXT);
		AstolfoUi.MEDIUM.draw(graphics, "Тяни / колесо", 378, 266, 8, AstolfoUi.MUTED);
		AstolfoUi.MEDIUM.draw(graphics, isApplied() ? "Применена ко всем экземплярам" : "Для выбранного предмета", 374, 335, 8, isApplied() ? AstolfoUi.ACCENT : AstolfoUi.MUTED);
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
		if (!VisualPreferences.animations) super.onClose();
		else closing = true;
	}

	private MouseButtonEvent local(MouseButtonEvent event) {
		return new MouseButtonEvent((event.x() - originX) / scale, (event.y() - originY) / scale, event.buttonInfo());
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		MouseButtonEvent mapped = local(event);
		if (closing) return true;
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
		if (mx >= 146 && mx <= 570 && my >= TOP && my < BOTTOM) {
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
			status = "";
			return true;
		}
		return super.keyPressed(event);
	}

	private boolean isVisuals() { return page.category != null; }
	private static boolean inPreview(double x, double y) { return x >= 372 && x < 562 && y >= 99 && y < 238; }
	private static Identifier itemId(Item item) { return BuiltInRegistries.ITEM.getKey(item); }
	private static String itemName(Item item) { return Component.translatable(item.getDescriptionId()).getString(); }
	private record Placed(AbstractWidget widget, int y) { }
	private record Nav(Page page, int y, AstolfoButton button) { }
	private record ModuleGroup(ModuleWidget header, int y, int height) { }
	private enum Page {
		HUD("HUD", "hud"),
		INVENTORY("Модели предметов", null), APPLIED("Применённые модели", null);
		final String label;
		final String category;
		Page(String label, String category) { this.label = label; this.category = category; }
	}
}
