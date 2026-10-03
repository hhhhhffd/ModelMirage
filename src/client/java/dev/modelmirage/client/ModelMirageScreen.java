package dev.modelmirage.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.modelmirage.client.catalog.CatalogModel;
import dev.modelmirage.client.catalog.CatalogService;
import dev.modelmirage.client.gui.AstolfoButton;
import dev.modelmirage.client.gui.AstolfoUi;
import dev.modelmirage.client.gui.ModelCard;
import dev.modelmirage.client.gui.ModuleWidget;
import dev.modelmirage.client.gui.VisualSettingWidget;
import dev.modelmirage.client.render.MascotAnimations;
import dev.modelmirage.client.visual.AppearanceScreen;
import dev.modelmirage.client.visual.HudEditorScreen;
import dev.modelmirage.client.visual.VisualModule;
import dev.modelmirage.client.visual.VisualPreferences;
import dev.modelmirage.client.visual.Visuals;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector2f;
import org.slf4j.LoggerFactory;

/** NL loadout composition: independent item tiles and separate self/other-player selections. */
public final class ModelMirageScreen extends Screen {
	private static final int W = 760, H = 460, SIDE = 145;
	private static final int GRID_X = 289, GRID_END = 607, TOP = 111, BOTTOM = 386;
	private static final int ACCENT = 0xFF6573FF, MUTED = 0xFF8893A3, LINE = 0xFF25303F;
	private final List<Item> allItems = BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR)
			.sorted((a, b) -> itemId(a).compareTo(itemId(b))).toList();
	private final List<AbstractWidget> chrome = new ArrayList<>();
	private final List<Placed> content = new ArrayList<>();
	private final List<ModuleGroup> groups = new ArrayList<>();
	private final List<Nav> navigation = new ArrayList<>();
	private final List<AstolfoButton> categories = new ArrayList<>();
	private List<CatalogModel> catalog = List.of();
	private EditBox search;
	private AstolfoButton back, apply, reset, hudEditor, mascotToggle, mascotEditor;
	private Page page = Page.CHANGER;
	private Category category = Category.ITEMS;
	private Item selectedItem;
	private CatalogModel selectedModel;
	private VisualModule bindingModule;
	private boolean capturingBind, dirty, closing, downloading, failed;
	private int saveDelay;
	private String status = "";
	private float scale = 1, originX, originY, opening, transition, scroll, targetScroll, scrollMaximum, tabY = 151;
	private boolean draggingPreview, draggingScroll;
	private float previewYaw, previewPitch;
	private long frame = System.nanoTime();

	public ModelMirageScreen() { super(Component.literal("ModelMirage")); }
	@Override public boolean isPauseScreen() { return false; }
	@Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) { }

	@Override
	protected void init() {
		chrome.clear(); content.clear(); groups.clear(); navigation.clear(); categories.clear();
		String query = search == null ? "" : search.getValue();
		search = new EditBox(font, 594, 16, 134, 15, Component.literal("Поиск"));
		search.setBordered(false); search.setHint(Component.literal("Поиск...")); search.setMaxLength(128); search.setValue(query);
		search.setResponder(value -> { targetScroll = scroll = 0; rebuild(); });
		chrome.add(addRenderableWidget(search));
		button(156, 10, 26, 26, "", false, this::save).setTooltip(Tooltip.create(Component.literal("Сохранить настройки")));
		button(735, 10, 16, 26, "x", false, this::onClose).compact = true;
		nav(Page.HUD, 119, "Visuals", "F");
		nav(Page.CHANGER, 151, "Inventory", "E");
		nav(Page.APPLIED, 183, "Применённые", "H");
		button(12, 367, 121, 23, "Обновить каталог", false, () -> CatalogService.refresh()).compact = true;
		button(115, 411, 20, 22, "", false, () -> minecraft.gui.setScreen(new AppearanceScreen(this))).symbol = "J";
		for (Category choice : Category.values()) {
			AstolfoButton tab = button(155 + choice.ordinal() * 149, 51, 143, 25, choice.label, false, () -> chooseCategory(choice));
			tab.compact = true;
			categories.add(tab);
		}
		back = button(GRID_X, 83, 25, 22, "<", false, () -> { selectedItem = null; selectedModel = null; clearSearch(); rebuild(); });
		apply = button(459, 408, 145, 29, "Применить", true, this::applyOrRetry);
		reset = button(615, 408, 130, 29, "Сброс", false, this::resetSelection);
		hudEditor = button(607, 53, 138, 24, "Редактор HUD", false, () -> { save(); if (!dirty) minecraft.gui.setScreen(new HudEditorScreen(this)); });
		mascotToggle = button(621, 349, 121, 24, "Показывать", false, () -> { Visuals.module("BoyKisser").enabled = !Visuals.enabled("BoyKisser"); changed(); });
		mascotToggle.setTooltip(Tooltip.create(Component.literal("ПКМ: назначить клавишу")));
		mascotEditor = button(621, 376, 121, 18, "Расположение", false, () -> { save(); if (!dirty) minecraft.gui.setScreen(new HudEditorScreen(this)); });
		mascotEditor.compact = true;
		catalog = CatalogService.models();
		restoreSelectedModel();
		rebuild();
	}

	private AstolfoButton button(int x, int y, int width, int height, String text, boolean primary, Runnable action) {
		AstolfoButton button = addRenderableWidget(new AstolfoButton(x, y, width, height, text, primary, action));
		button.loadout = true; chrome.add(button); return button;
	}
	private void nav(Page destination, int y, String name, String icon) {
		AstolfoButton button = button(9, y, 127, 27, name, false, () -> show(destination));
		button.navigation = true; button.symbol = icon; navigation.add(new Nav(destination, y, button));
	}
	private void show(Page destination) {
		page = destination; capturingBind = false; status = ""; transition = 0; clearSearch(); rebuild();
	}
	private void chooseCategory(Category choice) {
		category = choice; selectedItem = null; selectedModel = null; previewYaw = previewPitch = 0; transition = 0; status = "";
		restoreSelectedModel(); clearSearch(); rebuild();
	}
	private void clearSearch() { scroll = targetScroll = 0; search.setValue(""); }
	private void addContent(AbstractWidget widget, int y) { addRenderableWidget(widget); content.add(new Placed(widget, y)); }

	private void rebuild() {
		for (var placed : content) removeWidget(placed.widget());
		content.clear(); groups.clear();
		String query = search.getValue().trim().toLowerCase(Locale.ROOT);
		int end = TOP;
		if (page == Page.HUD) {
			int[] columnY = {89, 89};
			for (VisualModule module : Visuals.modules()) {
				if (module.id.equals("BoyKisser") || !(module.id + " " + module.name + " " + module.settings.values().stream().map(setting -> setting.name).collect(java.util.stream.Collectors.joining(" "))).toLowerCase(Locale.ROOT).contains(query)) continue;
				int column = columnY[0] <= columnY[1] ? 0 : 1;
				int x = 156 + column * 299, y = columnY[column];
				ModuleWidget header = new ModuleWidget(x, y, 288, module, () -> { bindingModule = module; capturingBind = true; }, this::changed);
				addContent(header, y);
				int next = y + 24;
				for (var setting : module.settings.values()) {
					var control = new VisualSettingWidget(x, next, 288, setting, this::changed);
					addContent(control, next); next += control.getHeight();
				}
				groups.add(new ModuleGroup(header, y, next - y + 4)); columnY[column] = next + 12;
			}
			end = Math.max(columnY[0], columnY[1]);
		} else if (category == Category.ITEMS && selectedItem == null) {
			int[] columnY = {TOP, TOP, TOP};
			for (Item item : allItems) {
				if (page == Page.APPLIED && !ItemModelOverrides.isEnabled(itemId(item))) continue;
				if (!(itemId(item) + " " + itemName(item)).toLowerCase(Locale.ROOT).contains(query)) continue;
				int column = itemColumn(item), y = columnY[column], x = GRID_X + column * 106;
				ModelCard card = new ModelCard(x, y, 100, new ItemStack(item), () -> selectItem(item));
				card.applied = ItemModelOverrides.isEnabled(itemId(item));
				card.setTooltip(Tooltip.create(Component.literal(itemName(item) + "\n" + itemId(item))));
				addContent(card, y); columnY[column] += 83;
			}
			end = Math.max(columnY[0], Math.max(columnY[1], columnY[2]));
		} else {
			List<CatalogModel> entries = catalog.stream().filter(entry -> entry.kind() == category.kind)
					.filter(entry -> selectedItem == null || entry.accepts(itemId(selectedItem)))
					.filter(entry -> (entry.name() + " " + entry.id() + " " + entry.group()).toLowerCase(Locale.ROOT).contains(query))
					.filter(entry -> page != Page.APPLIED || isSelectedForSlot(entry)).toList();
			for (int i = 0; i < entries.size(); i++) {
				CatalogModel entry = entries.get(i);
				int x = GRID_X + i % 3 * 106, y = TOP + i / 3 * 83;
				ModelCard card = new ModelCard(x, y, 100, entry, () -> selectModel(entry));
				card.selected = entry.equals(selectedModel); card.applied = isSelectedForSlot(entry);
				card.setTooltip(Tooltip.create(Component.literal(entry.name() + "\n" + entry.group() + (CatalogService.supports(entry) ? "" : "\nДля " + entry.format() + " нужен загрузчик"))));
				addContent(card, y); end = y + 83;
			}
		}
		int bottom = page == Page.HUD ? 389 : BOTTOM;
		scrollMaximum = Math.max(0, end - bottom); targetScroll = Math.clamp(targetScroll, 0, scrollMaximum);
	}
	private static int itemColumn(Item item) {
		String path = itemId(item).getPath();
		if (path.endsWith("_sword") || List.of("bow", "crossbow", "trident", "mace", "shield").contains(path)) return 0;
		if (path.endsWith("_axe") || path.endsWith("_pickaxe") || path.endsWith("_shovel") || path.endsWith("_hoe") || List.of("shears", "fishing_rod", "brush", "flint_and_steel").contains(path)) return 1;
		return 2;
	}
	private void selectItem(Item item) {
		selectedItem = item; category = Category.ITEMS; selectedModel = null; status = ""; restoreSelectedModel(); clearSearch(); rebuild();
	}
	private void restoreSelectedModel() {
		Identifier id = category.slot == null ? selectedItem == null ? null : ItemModelOverrides.selectedModel(itemId(selectedItem)) : CosmeticSelections.selected(category.slot);
		selectedModel = catalog.stream().filter(entry -> entry.kind() == category.kind && entry.modelId().equals(id)).findFirst().orElse(null);
		failed = downloading = false;
		if (selectedModel != null && CatalogService.supports(selectedModel)) selectModel(selectedModel);
	}
	private void selectModel(CatalogModel model) {
		selectedModel = model; failed = false; previewYaw = previewPitch = 0;
		if (!CatalogService.supports(model)) { downloading = false; status = "Эта модель появится после установки загрузчика " + model.format(); rebuild(); return; }
		downloading = !CatalogService.isLoaded(model); status = "";
		CatalogService.load(model).whenComplete((unused, failure) -> minecraft.execute(() -> {
			if (!model.equals(selectedModel)) return;
			downloading = false; failed = failure != null;
			status = failed ? "Не удалось загрузить. Нажми Повторить." : "Модель готова"; rebuild();
		}));
		rebuild();
	}
	private boolean isSelectedForSlot(CatalogModel model) {
		Identifier current = category.slot == null ? selectedItem == null ? null : ItemModelOverrides.selectedModel(itemId(selectedItem)) : CosmeticSelections.selected(category.slot);
		return model.modelId().equals(current);
	}
	private void applyOrRetry() {
		if (selectedModel == null) return;
		if (failed) { CatalogService.retry(selectedModel); selectModel(selectedModel); return; }
		if (!CatalogService.isLoaded(selectedModel)) return;
		try {
			if (category.slot != null) CosmeticSelections.set(category.slot, selectedModel.modelId());
			else if (selectedItem != null) ItemModelOverrides.setModel(itemId(selectedItem), selectedModel.modelId());
			else return;
			status = "Применено"; rebuild();
		} catch (IOException failure) { status = "Не удалось сохранить выбор"; LoggerFactory.getLogger("ModelMirage").error("Could not apply loadout model", failure); }
	}
	private void resetSelection() {
		try {
			if (category.slot != null) CosmeticSelections.set(category.slot, null);
			else if (selectedItem != null) ItemModelOverrides.setModel(itemId(selectedItem), null);
			else return;
			status = "Обычный вид восстановлен"; restoreSelectedModel(); rebuild();
		} catch (IOException failure) { status = "Не удалось сохранить выбор"; LoggerFactory.getLogger("ModelMirage").error("Could not reset loadout", failure); }
	}
	private void changed() { dirty = true; saveDelay = 10; }
	private void save() {
		try { Visuals.save(); dirty = false; status = "Настройки сохранены"; }
		catch (IOException failure) { saveDelay = 20; status = "Не удалось сохранить настройки"; LoggerFactory.getLogger("ModelMirage").error("Could not save loadout settings", failure); }
	}
	@Override public void tick() {
		if (dirty && --saveDelay <= 0) save();
		if (catalog != CatalogService.models()) {
			catalog = CatalogService.models();
			if (selectedModel != null) {
				var next = catalog.stream().filter(entry -> entry.id().equals(selectedModel.id())).findFirst().orElse(null);
				if (next == null) { selectedModel = null; failed = downloading = false; }
				else if (!next.equals(selectedModel)) selectModel(next);
			} else restoreSelectedModel();
			rebuild();
		}
		// Only visible tiles request previews; large catalogs do not download in full.
		if (page != Page.HUD) {
			int started = 0;
			for (var placed : content) {
				if (started == 3) break;
				if (placed.widget() instanceof ModelCard card && card.model != null && placed.y() - scroll < BOTTOM && placed.y() - scroll + card.getHeight() > TOP && CatalogService.supports(card.model)
						&& !CatalogService.isLoaded(card.model) && !CatalogService.isLoading(card.model) && !CatalogService.hasFailed(card.model)) { CatalogService.load(card.model); started++; }
			}
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		long now = System.nanoTime(); float seconds = Math.min((now - frame) / 1e9f, 0.1f); frame = now;
		opening = AstolfoUi.animate(opening, closing ? 0 : 1, 14, seconds);
		if (closing && opening < 0.005f) { super.onClose(); return; }
		transition = AstolfoUi.animate(transition, 1, 18, seconds); scroll = AstolfoUi.animate(scroll, targetScroll, 18, seconds);
		scale = Math.min(2f / minecraft.getWindow().getGuiScale() * VisualPreferences.guiScale, Math.min((width - 16f) / W, (height - 16f) / H)) * (0.94f + opening * 0.06f);
		originX = (width - W * scale) / 2; originY = (height - H * scale) / 2;
		int mx = (int) ((mouseX - originX) / scale), my = (int) ((mouseY - originY) / scale);
		graphics.fill(0, 0, width, height, AstolfoUi.opacity(0x7704080E, opening * VisualPreferences.opacity));
		graphics.pose().pushMatrix(); graphics.pose().translate(originX, originY); graphics.pose().scale(scale, scale);
		AstolfoUi.alpha = opening;
		AstolfoUi.shadow(graphics, 0, 0, W, H, 11);
		AstolfoUi.shape(graphics, 0, 0, W, H, 11, 0, 0xFF152130, 0xFF0E1520);
		AstolfoUi.rounded(graphics, 0, 0, SIDE, H, 11, 0xFF0B0F15);
		graphics.fill(SIDE - 10, 0, SIDE, H, AstolfoUi.opacity(0xFF0B0F15, opening));
		graphics.fill(SIDE, 0, SIDE + 1, H, AstolfoUi.opacity(LINE, opening));
		graphics.fill(SIDE + 10, 43, W - 10, 44, AstolfoUi.opacity(LINE, opening));
		AstolfoUi.BOLD.draw(graphics, "MM", 17, 20, 15, 0xFF82CFFF);
		AstolfoUi.SEMIBOLD.draw(graphics, "ModelMirage", 48, 18, 11, AstolfoUi.TEXT);
		AstolfoUi.MEDIUM.draw(graphics, "Minecraft", 49, 34, 7, MUTED);
		graphics.fill(12, 58, SIDE - 12, 59, AstolfoUi.opacity(LINE, opening));
		AstolfoUi.MEDIUM.draw(graphics, "ОБЩЕЕ", 17, 98, 7, MUTED);
		for (var nav : navigation) {
			nav.button().selected = page == nav.page();
			if (page == nav.page()) tabY = AstolfoUi.animate(tabY, nav.y(), 18, seconds);
		}
		AstolfoUi.rounded(graphics, 9, Math.round(tabY), 127, 27, 6, 0xFF252932);
		AstolfoUi.rounded(graphics, 188, 10, 254, 26, 5, 0xFF111A26);
		AstolfoUi.MEDIUM.draw(graphics, "Локальный набор", 200, 19, 9, 0xFFBCC5D1);
		AstolfoUi.MEDIUM.draw(graphics, "ModelMirage", 342, 20, 7, MUTED);
		AstolfoUi.rounded(graphics, 571, 10, 158, 26, 5, 0xFF111A26);
		AstolfoUi.ICONS.draw(graphics, "I", 578, 18, 10, MUTED);
		graphics.fill(12, 400, SIDE - 12, 401, AstolfoUi.opacity(LINE, opening));
		AstolfoUi.SEMIBOLD.draw(graphics, AstolfoUi.SEMIBOLD.fit(minecraft.player.getName().getString(), 9, 93), 14, 414, 9, AstolfoUi.TEXT);
		AstolfoUi.MEDIUM.draw(graphics, CatalogService.loading() ? "Загрузка каталога..." : CatalogService.cached() ? "Каталог из кеша" : CatalogService.error().isEmpty() ? "Каталог подключён" : "Каталог недоступен", 14, 432, 7, MUTED);
		boolean changer = page != Page.HUD;
		for (int i = 0; i < categories.size(); i++) { categories.get(i).visible = changer; categories.get(i).selected = i == category.ordinal(); }
		back.visible = changer && category == Category.ITEMS && selectedItem != null;
		apply.visible = reset.visible = changer;
		apply.active = selectedModel != null && !downloading && CatalogService.supports(selectedModel) && (failed || CatalogService.isLoaded(selectedModel));
		reset.active = category.slot != null ? category == Category.MASCOTS || CosmeticSelections.selected(category.slot) != null : selectedItem != null && ItemModelOverrides.isEnabled(itemId(selectedItem));
		apply.setMessage(Component.literal(failed ? "Повторить" : downloading ? "Загрузка..." : selectedModel != null && isSelectedForSlot(selectedModel) ? "Применено" : "Применить"));
		hudEditor.visible = !changer;
		mascotToggle.visible = changer && category == Category.MASCOTS;
		mascotEditor.visible = mascotToggle.visible;
		mascotToggle.selected = Visuals.enabled("BoyKisser");
		mascotToggle.setMessage(Component.literal(Visuals.enabled("BoyKisser") ? "Показ: включён" : "Показ: выключен"));
		if (changer) drawLoadout(graphics, mx, my);
		else AstolfoUi.SEMIBOLD.draw(graphics, "HUD и визуальные настройки", 156, 61, 11, AstolfoUi.TEXT);
		for (AbstractWidget widget : chrome) widget.extractRenderState(graphics, mx, my, delta);
		AstolfoUi.shape(graphics, 164, 17, 10, 12, 1, 1, 0xFFD5DCE6, 0xFFD5DCE6);
		AstolfoUi.shape(graphics, 166, 17, 5, 4, 0, 1, 0xFFD5DCE6, 0xFFD5DCE6);
		AstolfoUi.shape(graphics, 167, 25, 4, 2, 0, 0, 0xFFD5DCE6, 0xFFD5DCE6);
		int left = changer ? GRID_X - 2 : 152, right = changer ? GRID_END + 1 : 750, top = changer ? TOP : 89, bottom = changer ? BOTTOM : 389;
		graphics.enableScissor(left, top, right, bottom);
		for (var group : groups) AstolfoUi.rounded(graphics, group.header().getX(), Math.round(group.y() - scroll), 288, group.height(), 7, AstolfoUi.PANEL);
		for (var placed : content) {
			var widget = placed.widget(); widget.setY(Math.round(placed.y() - scroll + (1 - transition) * 6));
			widget.visible = widget.getY() < bottom && widget.getY() + widget.getHeight() > top;
			if (widget instanceof ModelCard card && card.selected) { card.yaw = previewYaw; card.pitch = previewPitch; }
			widget.extractRenderState(graphics, my >= top && my < bottom ? mx : -1000, my, delta);
		}
		graphics.disableScissor();
		if (content.isEmpty()) {
			String text = CatalogService.loading() ? "Загрузка каталога..." : category.kind == CatalogModel.Kind.PLAYER && queryEmpty() ? "Моделей игроков пока нет" : "Ничего не найдено";
			AstolfoUi.SEMIBOLD.draw(graphics, AstolfoUi.SEMIBOLD.fit(text, 10, right - left - 18), left + 9, top + 20, 10, AstolfoUi.TEXT);
			AstolfoUi.MEDIUM.draw(graphics, queryEmpty() ? "Добавь модель в каталог" : "Попробуй другой поиск", left + 9, top + 39, 8, MUTED);
		}
		if (scrollMaximum > 0) {
			int track = bottom - top, thumb = Math.max(20, Math.round(track * track / (scrollMaximum + track)));
			AstolfoUi.rounded(graphics, right - 3, top, 2, track, 1, LINE);
			AstolfoUi.rounded(graphics, right - 3, top + Math.round(scroll / scrollMaximum * (track - thumb)), 2, thumb, 1, ACCENT);
		}
		graphics.fill(SIDE + 10, 397, W - 10, 398, AstolfoUi.opacity(LINE, opening));
		String footer = capturingBind ? "Клавиша для " + bindingModule.id + ": Backspace: удалить / Esc: отменить" : !status.isEmpty() ? status : selectedModel != null ? selectedModel.name() : "Выбери предмет, модель или персонажа";
		AstolfoUi.MEDIUM.draw(graphics, AstolfoUi.MEDIUM.fit(footer, 8, changer ? 291 : 585), 156, 418, 8, failed ? 0xFFFF7C8F : MUTED);
		AstolfoUi.alpha = 1; graphics.pose().popMatrix();
	}
	private boolean queryEmpty() { return search.getValue().isBlank(); }
	private void drawLoadout(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		AstolfoUi.rounded(graphics, 155, 83, 122, 303, 7, 0x80101823);
		AstolfoUi.rounded(graphics, 617, 83, 130, 303, 7, 0x80101823);
		AstolfoUi.SEMIBOLD.draw(graphics, "Своя модель", 164, 94, 9, category == Category.SELF ? 0xFFADB6FF : AstolfoUi.TEXT);
		AstolfoUi.SEMIBOLD.draw(graphics, category == Category.MASCOTS ? "Персонаж HUD" : "Другие игроки", 626, 94, 9, category == Category.OTHERS ? 0xFFADB6FF : AstolfoUi.TEXT);
		drawPlayer(graphics, false, 160, 110, 272, 313, mouseX, mouseY);
		if (category == Category.MASCOTS) {
			Identifier preview = selectedModel != null && CatalogService.isLoaded(selectedModel) ? selectedModel.modelId() : CosmeticSelections.selected(CosmeticSelections.Slot.MASCOT);
			float ratio = Math.min(104f / MascotAnimations.width(preview), 170f / MascotAnimations.height(preview));
			int w = Math.round(MascotAnimations.width(preview) * ratio), h = Math.round(MascotAnimations.height(preview) * ratio);
			MascotAnimations.draw(graphics, preview, 682 - w / 2, 210 - h / 2, w, h, AstolfoUi.opacity(0xFFFFFFFF, opening));
		} else drawPlayer(graphics, true, 622, 110, 742, 313, mouseX, mouseY);
		AstolfoUi.MEDIUM.draw(graphics, playerLabel(CosmeticSelections.Slot.SELF), 164, 319, 7, MUTED);
		AstolfoUi.MEDIUM.draw(graphics, category == Category.MASCOTS && selectedModel != null ? AstolfoUi.MEDIUM.fit(selectedModel.name(), 7, 112) : playerLabel(CosmeticSelections.Slot.OTHERS), 626, 319, 7, MUTED);
		if (category == Category.ITEMS && selectedItem == null) {
			String[] headings = {"Оружие", "Инструменты", "Предметы"};
			for (int i = 0; i < headings.length; i++) AstolfoUi.MEDIUM.draw(graphics, headings[i], GRID_X + i * 106 + 5, 94, 8, 0xFFC8D0DC);
		} else {
			String title = selectedItem == null ? category.label : itemName(selectedItem);
			AstolfoUi.SEMIBOLD.draw(graphics, AstolfoUi.SEMIBOLD.fit(title, 9, GRID_END - GRID_X - 40), back.visible ? GRID_X + 33 : GRID_X + 5, 94, 9, AstolfoUi.TEXT);
		}
		EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS};
		for (int i = 0; i < slots.length; i++) {
			int x = 163 + i * 36;
			ItemStack equipment = minecraft.player.getItemBySlot(slots[i]);
			AstolfoUi.rounded(graphics, x, 340, 32, 34, 4, 0xFF151E2B);
			if (equipment.isEmpty()) AstolfoUi.MEDIUM.draw(graphics, "Нет", x + 8, 351, 7, MUTED);
			else graphics.item(equipment, x + 8, 348);
			graphics.fill(x, 372, x + 32, 374, AstolfoUi.opacity(equipment.isEmpty() ? LINE : ACCENT, opening));
		}
	}
	private String playerLabel(CosmeticSelections.Slot slot) {
		Identifier selected = CosmeticSelections.selected(slot);
		return AstolfoUi.MEDIUM.fit(catalog.stream().filter(entry -> entry.modelId().equals(selected)).map(CatalogModel::name).findFirst().orElse(selected == null ? "Обычная модель" : "Нет в каталоге"), 7, 112);
	}
	private void drawPlayer(GuiGraphicsExtractor graphics, boolean others, int x0, int y0, int x1, int y1, int mouseX, int mouseY) {
		var player = others ? minecraft.level.players().stream().filter(candidate -> candidate != minecraft.player).findFirst().orElse(minecraft.player) : minecraft.player;
		var renderer = minecraft.getEntityRenderDispatcher().getRenderer(player);
		AvatarRenderState state = (AvatarRenderState) renderer.createRenderState(player, 1);
		state.shadowPieces.clear(); state.outlineColor = 0; state.nameTag = null;
		if (others && player == minecraft.player) { state.id = -1; state.skin = DefaultPlayerSkin.getDefaultSkin(); }
		float rotation = (float) Math.atan(((x0 + x1) / 2f - mouseX) / 100);
		state.bodyRot = 180 + rotation * 22; state.yRot = rotation * 22; state.xRot = 0;
		state.scale = 1;
		Quaternionf facing = new Quaternionf().rotateZ((float) Math.PI);
		// Unlike item states, entity previews do not capture graphics.pose() in 26.2.
		Vector2f topLeft = graphics.pose().transformPosition(x0, y0, new Vector2f());
		Vector2f bottomRight = graphics.pose().transformPosition(x1, y1, new Vector2f());
		graphics.enableScissor(x0, y0, x1, y1);
		graphics.entity(state, 85 * graphics.pose().m00(), new Vector3f(0, state.boundingBoxHeight / 2f, 0), facing, null,
				Math.round(topLeft.x), Math.round(topLeft.y), Math.round(bottomRight.x), Math.round(bottomRight.y));
		graphics.disableScissor();
	}
	@Override public void onClose() {
		if (dirty) { save(); if (dirty) return; }
		if (VisualPreferences.animations) closing = true; else super.onClose();
	}
	private MouseButtonEvent local(MouseButtonEvent event) { return new MouseButtonEvent((event.x() - originX) / scale, (event.y() - originY) / scale, event.buttonInfo()); }
	@Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (closing) return true;
		var mapped = local(event);
		if (category == Category.MASCOTS && page != Page.HUD && (mapped.button() == 1 || mapped.button() == 2)
				&& mascotToggle.isMouseOver(mapped.x(), mapped.y())) { bindingModule = Visuals.module("BoyKisser"); capturingBind = true; return true; }
		int top = page == Page.HUD ? 89 : TOP, bottom = page == Page.HUD ? 389 : BOTTOM, right = page == Page.HUD ? 750 : GRID_END + 1;
		if (mapped.button() == 0 && scrollMaximum > 0 && mapped.x() >= right - 7 && mapped.x() <= right && mapped.y() >= top && mapped.y() < bottom) { draggingScroll = true; scrollTo(mapped.y(), top, bottom); return true; }
		if (page != Page.HUD && mapped.button() == 0) {
			if (mapped.x() >= 155 && mapped.x() < 277 && mapped.y() >= 83 && mapped.y() < 335) { chooseCategory(Category.SELF); return true; }
			if (mapped.x() >= 617 && mapped.x() < 747 && mapped.y() >= 83 && mapped.y() < 335) { chooseCategory(Category.OTHERS); return true; }
		}
		boolean inside = mapped.y() >= top && mapped.y() < bottom;
		if (!inside) for (var placed : content) placed.widget().active = false;
		boolean handled = super.mouseClicked(mapped, doubleClick);
		if (!inside) for (var placed : content) placed.widget().active = true;
		if (inside && handled && getFocused() instanceof ModelCard card && card.model != null && CatalogService.isLoaded(card.model) && card.model.kind() != CatalogModel.Kind.MASCOT) draggingPreview = true;
		return handled;
	}
	private void scrollTo(double y, int top, int bottom) { scroll = targetScroll = Math.clamp((float) ((y - top) / (bottom - top)) * scrollMaximum, 0, scrollMaximum); }
	@Override public boolean mouseReleased(MouseButtonEvent event) { draggingPreview = draggingScroll = false; return super.mouseReleased(local(event)); }
	@Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (draggingScroll) { scrollTo(local(event).y(), page == Page.HUD ? 89 : TOP, page == Page.HUD ? 389 : BOTTOM); return true; }
		if (draggingPreview) { previewYaw += (float) (dx / scale * 0.015); previewPitch = Math.clamp(previewPitch + (float) (dy / scale * 0.015), -1.3f, 1.3f); return true; }
		return super.mouseDragged(local(event), dx / scale, dy / scale);
	}
	@Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		double mx = (x - originX) / scale, my = (y - originY) / scale;
		if (mx >= (page == Page.HUD ? 152 : GRID_X) && mx <= (page == Page.HUD ? 750 : GRID_END + 1) && my >= (page == Page.HUD ? 89 : TOP) && my < (page == Page.HUD ? 389 : BOTTOM)) { targetScroll = Math.clamp(targetScroll - (float) vertical * 48, 0, scrollMaximum); return true; }
		return super.mouseScrolled(mx, my, horizontal, vertical);
	}
	@Override public boolean keyPressed(KeyEvent event) {
		if (capturingBind) { if (event.key() != InputConstants.KEY_ESCAPE) { bindingModule.bind = event.key() == InputConstants.KEY_BACKSPACE ? -1 : event.key(); changed(); } capturingBind = false; return true; }
		if (event.key() == InputConstants.KEY_ESCAPE && page != Page.HUD && selectedItem != null && !search.isFocused()) { selectedItem = null; selectedModel = null; clearSearch(); rebuild(); return true; }
		return super.keyPressed(event);
	}
	private static Identifier itemId(Item item) { return BuiltInRegistries.ITEM.getKey(item); }
	private static String itemName(Item item) { return Component.translatable(item.getDescriptionId()).getString(); }
	private record Placed(AbstractWidget widget, int y) { }
	private record ModuleGroup(ModuleWidget header, int y, int height) { }
	private record Nav(Page page, int y, AstolfoButton button) { }
	private enum Page { CHANGER, APPLIED, HUD }
	private enum Category {
		ITEMS("Предметы", CatalogModel.Kind.ITEM, null), SELF("Своя модель", CatalogModel.Kind.PLAYER, CosmeticSelections.Slot.SELF),
		OTHERS("Другие игроки", CatalogModel.Kind.PLAYER, CosmeticSelections.Slot.OTHERS), MASCOTS("Персонажи", CatalogModel.Kind.MASCOT, CosmeticSelections.Slot.MASCOT);
		final String label; final CatalogModel.Kind kind; final CosmeticSelections.Slot slot;
		Category(String label, CatalogModel.Kind kind, CosmeticSelections.Slot slot) { this.label = label; this.kind = kind; this.slot = slot; }
	}
}
