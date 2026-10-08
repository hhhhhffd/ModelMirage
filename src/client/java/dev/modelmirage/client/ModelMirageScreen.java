package dev.modelmirage.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.modelmirage.client.catalog.CatalogModel;
import dev.modelmirage.client.catalog.CatalogService;
import dev.modelmirage.client.gui.AstolfoUi;
import dev.modelmirage.client.gui.ModelTile;
import dev.modelmirage.client.gui.TileGrid;
import dev.modelmirage.client.gui.kit.Button;
import dev.modelmirage.client.gui.kit.ButtonRow;
import dev.modelmirage.client.gui.kit.ColorRow;
import dev.modelmirage.client.gui.kit.Columns;
import dev.modelmirage.client.gui.kit.Control;
import dev.modelmirage.client.gui.kit.Group;
import dev.modelmirage.client.gui.kit.InfoRow;
import dev.modelmirage.client.gui.kit.KeyRow;
import dev.modelmirage.client.gui.kit.Paint;
import dev.modelmirage.client.gui.kit.Row;
import dev.modelmirage.client.gui.kit.ScrollView;
import dev.modelmirage.client.gui.kit.SearchField;
import dev.modelmirage.client.gui.kit.Settings;
import dev.modelmirage.client.gui.kit.SliderRow;
import dev.modelmirage.client.gui.kit.Surface;
import dev.modelmirage.client.gui.kit.Theme;
import dev.modelmirage.client.gui.kit.ToggleRow;
import dev.modelmirage.client.render.MascotAnimations;
import dev.modelmirage.client.render.RuntimeModels;
import dev.modelmirage.client.visual.HudEditorScreen;
import dev.modelmirage.client.visual.VisualModule;
import dev.modelmirage.client.visual.VisualPreferences;
import dev.modelmirage.client.visual.Visuals;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.slf4j.LoggerFactory;

/** Glass loadout and visuals menu: sidebar navigation, top bar with search, card-based pages. */
public final class ModelMirageScreen extends Screen {
	private static final float W = 720, H = 460, SIDE = 172, TOP = 52, PAD = 14, INSPECTOR = 226;
	private final List<Item> allItems = BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR)
			.sorted((a, b) -> itemId(a).compareTo(itemId(b))).toList();
	private final Surface surface = new Surface();
	private final List<Nav> navigation = new ArrayList<>();
	private final List<Control> roots = new ArrayList<>();
	private final ScrollView scroll = new ScrollView(new Columns());
	private final Inspector inspector = new Inspector();
	private final SearchField search = new SearchField("Поиск", value -> { scroll.reset(); rebuild(); });
	private Button close, editor, refresh;
	private Crumb crumb;
	private TileGrid grid;
	private List<CatalogModel> catalog = List.of();
	private Page page = Page.ITEMS;
	private Item selectedItem;
	private CatalogModel selectedModel;
	private boolean dirty, preferencesDirty, closing, downloading, failed;
	private int saveDelay, preferencesDelay;
	/** Menu scale in use; follows the preference only when no slider is held, so the track never slides away mid-drag. */
	private float menuScale = VisualPreferences.guiScale;
	private String toast = "";
	private boolean toastError;
	private long toastTime;
	private float scale = 1, originX, originY, opening, transition, navY = -1, previewYaw, previewPitch;

	public ModelMirageScreen() { super(Component.literal("ModelMirage")); }
	@Override public boolean isPauseScreen() { return false; }
	@Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) { }

	@Override
	protected void init() {
		navigation.clear();
		roots.clear();
		close = new Button("", Button.Style.PLAIN, this::onClose);
		editor = new Button("Редактор HUD", Button.Style.SECONDARY, this::openEditor);
		editor.icon = "H";
		editor.height = 22;
		editor.textSize = Theme.CAPTION + 0.5f;
		refresh = new Button("", Button.Style.PLAIN, () -> { CatalogService.refresh(); notify("Обновляю каталог...", false); });
		refresh.icon = "D";
		refresh.height = 22;
		crumb = new Crumb();
		float y = 74;
		Section section = null;
		for (Page destination : Page.values()) {
			if (destination.section != section) { section = destination.section; y += section == Section.LOADOUT ? 0 : 12; navigation.add(new Nav(null, section, y)); y += 18; }
			navigation.add(new Nav(destination, section, y));
			y += 26;
		}
		roots.addAll(navigation);
		roots.add(scroll);
		roots.add(inspector);
		roots.add(search);
		roots.add(editor);
		roots.add(refresh);
		roots.add(crumb);
		roots.add(close);
		catalog = CatalogService.models();
		// init() also runs on resize and when returning from the HUD editor: keep an unapplied selection.
		if (selectedModel == null) restoreSelectedModel();
		rebuild();
	}

	private void show(Page destination) {
		if (page == destination) return;
		page = destination;
		transition = 0;
		selectedItem = null;
		previewYaw = previewPitch = 0;
		surface.focus(null);
		search.clear();
		scroll.reset();
		restoreSelectedModel();
		rebuild();
	}

	private void openEditor() {
		save();
		if (!dirty) minecraft.gui.setScreen(new HudEditorScreen(this));
	}

	private void notify(String message, boolean error) {
		toast = message;
		toastError = error;
		toastTime = System.currentTimeMillis();
	}

	private String query() { return search.text().trim().toLowerCase(Locale.ROOT); }

	/** Rebuilds the scrollable content of the current page from the model state and the search query. */
	private void rebuild() {
		String query = query();
		grid = null;
		if (page.loadout()) {
			grid = new TileGrid();
			if (page == Page.ITEMS && selectedItem == null) {
				List<ModelTile> weapons = new ArrayList<>(), tools = new ArrayList<>(), other = new ArrayList<>();
				for (Item item : allItems) {
					if (!(itemId(item) + " " + itemName(item)).toLowerCase(Locale.ROOT).contains(query)) continue;
					ModelTile tile = new ModelTile(new ItemStack(item), () -> selectItem(item));
					tile.applied = () -> ItemModelOverrides.isEnabled(itemId(item));
					(switch (itemColumn(item)) { case 0 -> weapons; case 1 -> tools; default -> other; }).add(tile);
				}
				grid.sections.add(new TileGrid.Section("Оружие", weapons));
				grid.sections.add(new TileGrid.Section("Инструменты", tools));
				grid.sections.add(new TileGrid.Section("Предметы", other));
			} else {
				List<ModelTile> tiles = new ArrayList<>();
				for (CatalogModel entry : catalog) {
					if (entry.kind() != page.kind || selectedItem != null && !entry.accepts(itemId(selectedItem))) continue;
					if (!(entry.name() + " " + entry.id() + " " + entry.group()).toLowerCase(Locale.ROOT).contains(query)) continue;
					ModelTile tile = new ModelTile(entry, () -> selectModel(entry));
					tile.selected = () -> entry.equals(selectedModel);
					tile.applied = () -> isApplied(entry);
					tiles.add(tile);
				}
				grid.sections.add(new TileGrid.Section(selectedItem == null ? "" : "Модели для предмета", tiles));
			}
			scroll.content = grid;
			return;
		}
		Columns columns = new Columns();
		switch (page) {
			case HUD -> {
				Group general = new Group().caption("F", "Общие").title("Интерфейс");
				VisualModule hud = Visuals.module("Interface");
				for (VisualModule.Setting setting : hud.settings.values()) general.add(Settings.row(setting, this::changed));
				general.add(new ButtonRow("Расположение панелей", "Открыть", this::openEditor));
				columns.children.add(general);
				boolean first = true;
				for (VisualModule module : Visuals.modules()) {
					if (!module.isPanel() || !matches(module, query)) continue;
					Group group = Settings.module(module, this::changed);
					if (first) group.caption("G", "Панели");
					first = false;
					columns.children.add(group);
				}
			}
			case SCREEN -> {
				for (VisualModule module : Visuals.modules()) {
					if (!module.category.equals("hud") || module.id.equals("Interface") || !matches(module, query)) continue;
					// Every card is captioned so the two columns start on the same line.
					columns.children.add(Settings.module(module, this::changed).caption("F", module.id.equals("Crosshair") ? "Прицел" : "Интерфейс игры"));
				}
			}
			case APPLIED -> buildApplied(columns, query);
			case SETTINGS -> buildSettings(columns, query);
			default -> throw new IllegalStateException("Unexpected page " + page);
		}
		scroll.content = columns;
	}

	private static boolean matches(VisualModule module, String query) {
		if (query.isEmpty()) return true;
		StringBuilder text = new StringBuilder(module.id).append(' ').append(module.name);
		for (VisualModule.Setting setting : module.settings.values()) text.append(' ').append(setting.name);
		return text.toString().toLowerCase(Locale.ROOT).contains(query);
	}

	private void buildApplied(Columns columns, String query) {
		Group items = new Group().caption("E", "Предметы").title("Модели предметов");
		ItemModelOverrides.all().forEach((itemKey, model) -> {
			Item item = BuiltInRegistries.ITEM.getValue(itemKey);
			String name = item == Items.AIR ? itemKey.toString() : itemName(item);
			if (!(name + " " + itemKey + " " + modelName(model)).toLowerCase(Locale.ROOT).contains(query)) return;
			items.add(new AppliedRow(new ItemStack(item), name, modelName(model), () -> {
				try { ItemModelOverrides.setModel(itemKey, null); notify("Сброшено: " + name, false); rebuild(); }
				catch (IOException failure) { saveFailed("Could not reset item override", failure); }
			}));
		});
		if (items.rows.isEmpty()) items.add(new InfoRow(query.isEmpty() ? "Пока ничего не применено" : "Ничего не найдено", () -> ""));
		columns.children.add(items);
		Group players = new Group().caption("L", "Персонажи").title("Игроки и HUD");
		for (CosmeticSelections.Slot slot : CosmeticSelections.Slot.values()) {
			Identifier selected = CosmeticSelections.selected(slot);
			String label = switch (slot) { case SELF -> "Своя модель"; case OTHERS -> "Другие игроки"; case MASCOT -> "Персонаж HUD"; };
			if (selected == null) players.add(new InfoRow(label, () -> "Обычная"));
			else players.add(new AppliedRow(ItemStack.EMPTY, label, modelName(selected), () -> {
				try { CosmeticSelections.set(slot, null); notify("Сброшено: " + label, false); rebuild(); }
				catch (IOException failure) { saveFailed("Could not reset cosmetic", failure); }
			}));
		}
		columns.children.add(players);
	}

	private void buildSettings(Columns columns, String query) {
		Group look = new Group().caption("J", "Оформление").title("Внешний вид");
		look.add(new ColorRow("Акцентный цвет", () -> AstolfoUi.ACCENT, color -> { AstolfoUi.ACCENT = color; preferencesChanged(); }));
		look.add(new SliderRow("Масштаб меню", 0.75, 1.5, 0.05, "x", () -> VisualPreferences.guiScale, value -> { VisualPreferences.guiScale = (float) value; preferencesChanged(); }));
		look.add(new SliderRow("Размытие стекла", 0, 30, 1, "", () -> VisualPreferences.blur, value -> { VisualPreferences.blur = (float) value; preferencesChanged(); }));
		look.add(new SliderRow("Затемнение фона", 0, 1, 0.05, "", () -> VisualPreferences.opacity, value -> { VisualPreferences.opacity = (float) value; preferencesChanged(); }));
		look.add(new ToggleRow("Анимации", () -> VisualPreferences.animations, value -> { VisualPreferences.animations = value; preferencesChanged(); }));
		Group source = new Group().caption("E", "Каталог").title("Источник моделей");
		source.add(new InfoRow("Статус", ModelMirageScreen::catalogStatus));
		source.add(new InfoRow("Моделей", () -> String.valueOf(CatalogService.models().size())));
		source.add(new ButtonRow("Каталог", "Обновить", () -> { CatalogService.refresh(); notify("Обновляю каталог...", false); }));
		Group about = new Group().caption("D", "О моде").title("ModelMirage");
		about.add(new InfoRow("Версия", () -> FabricLoader.getInstance().getModContainer("modelmirage").map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("?")));
		about.add(new InfoRow("Открыть меню", () -> "Right Shift"));
		for (Group group : List.of(look, source, about)) {
			boolean visible = query.isEmpty() || group.title.toLowerCase(Locale.ROOT).contains(query) || group.caption.toLowerCase(Locale.ROOT).contains(query)
					|| group.rows.stream().anyMatch(row -> row instanceof Row line && line.label.toLowerCase(Locale.ROOT).contains(query));
			if (visible) columns.children.add(group);
		}
	}

	private void preferencesChanged() { preferencesDirty = true; preferencesDelay = 10; }

	private void savePreferences() {
		try { VisualPreferences.save(); preferencesDirty = false; }
		catch (IOException failure) { preferencesDelay = 20; saveFailed("Could not save appearance", failure); }
	}

	private void saveFailed(String log, IOException failure) {
		notify("Не удалось сохранить", true);
		LoggerFactory.getLogger("ModelMirage").error(log, failure);
	}

	private String modelName(Identifier model) {
		return catalog.stream().filter(entry -> entry.modelId().equals(model)).map(CatalogModel::name).findFirst().orElse("Нет в каталоге");
	}

	private static String catalogStatus() {
		if (CatalogService.loading()) return "Загрузка...";
		if (CatalogService.cached()) return "Из кеша";
		return CatalogService.error().isEmpty() ? "Подключён" : "Недоступен";
	}

	private static int itemColumn(Item item) {
		String path = itemId(item).getPath();
		if (path.endsWith("_sword") || path.endsWith("_spear") || List.of("bow", "crossbow", "trident", "mace", "shield").contains(path)) return 0;
		if (path.endsWith("_axe") || path.endsWith("_pickaxe") || path.endsWith("_shovel") || path.endsWith("_hoe") || List.of("shears", "fishing_rod", "brush", "flint_and_steel").contains(path)) return 1;
		return 2;
	}

	private void selectItem(Item item) {
		selectedItem = item;
		previewYaw = previewPitch = 0;
		surface.focus(null);
		search.clear();
		scroll.reset();
		transition = 0;
		restoreSelectedModel();
		rebuild();
	}

	private void back() {
		selectedItem = null;
		selectedModel = null;
		search.clear();
		scroll.reset();
		transition = 0;
		rebuild();
	}

	private Identifier appliedId() {
		if (page.slot != null) return CosmeticSelections.selected(page.slot);
		return selectedItem == null ? null : ItemModelOverrides.selectedModel(itemId(selectedItem));
	}

	private boolean isApplied(CatalogModel model) { return model.modelId().equals(appliedId()); }

	private void restoreSelectedModel() {
		Identifier id = page.loadout() ? appliedId() : null;
		selectedModel = catalog.stream().filter(entry -> entry.kind() == page.kind && entry.modelId().equals(id)).findFirst().orElse(null);
		failed = downloading = false;
		if (selectedModel != null && CatalogService.supports(selectedModel)) selectModel(selectedModel);
	}

	private void selectModel(CatalogModel model) {
		selectedModel = model;
		failed = false;
		previewYaw = previewPitch = 0;
		if (!CatalogService.supports(model)) { downloading = false; notify("Для формата " + model.format() + " нужен загрузчик", true); return; }
		downloading = !CatalogService.isLoaded(model);
		CatalogService.load(model).whenComplete((unused, failure) -> minecraft.execute(() -> {
			if (!model.equals(selectedModel)) return;
			downloading = false;
			failed = failure != null;
			if (failed) notify("Не удалось загрузить модель", true);
		}));
	}

	private void applyOrRetry() {
		if (selectedModel == null) return;
		if (failed) { CatalogService.retry(selectedModel); selectModel(selectedModel); return; }
		if (!CatalogService.isLoaded(selectedModel)) return;
		try {
			if (page.slot != null) CosmeticSelections.set(page.slot, selectedModel.modelId());
			else if (selectedItem != null) ItemModelOverrides.setModel(itemId(selectedItem), selectedModel.modelId());
			else return;
			notify("Применено: " + selectedModel.name(), false);
		} catch (IOException failure) { saveFailed("Could not apply loadout model", failure); }
	}

	private void resetSelection() {
		try {
			if (page.slot != null) CosmeticSelections.set(page.slot, null);
			else if (selectedItem != null) ItemModelOverrides.setModel(itemId(selectedItem), null);
			else return;
			notify("Обычный вид восстановлен", false);
			restoreSelectedModel();
		} catch (IOException failure) { saveFailed("Could not reset loadout", failure); }
	}

	private boolean canReset() {
		if (page == Page.MASCOTS) return CosmeticSelections.selected(CosmeticSelections.Slot.MASCOT) != null;
		if (page.slot != null) return CosmeticSelections.selected(page.slot) != null;
		return selectedItem != null && ItemModelOverrides.isEnabled(itemId(selectedItem));
	}

	private void changed() { dirty = true; saveDelay = 10; }

	private void save() {
		try { Visuals.save(); dirty = false; }
		catch (IOException failure) { saveDelay = 20; saveFailed("Could not save visual settings", failure); }
	}

	@Override
	public void tick() {
		if (dirty && --saveDelay <= 0) save();
		if (preferencesDirty && --preferencesDelay <= 0) savePreferences();
		if (catalog != CatalogService.models()) {
			catalog = CatalogService.models();
			if (selectedModel != null) {
				CatalogModel next = catalog.stream().filter(entry -> entry.id().equals(selectedModel.id())).findFirst().orElse(null);
				if (next == null) { selectedModel = null; failed = downloading = false; }
				else if (!next.equals(selectedModel)) selectModel(next);
			} else restoreSelectedModel();
			rebuild();
		}
		// Only visible tiles request previews; large catalogs never download in full.
		if (grid != null) {
			int started = 0;
			for (ModelTile tile : grid.tiles()) {
				if (started == 3) break;
				if (tile.model != null && tile.y + tile.height > scroll.y && tile.y < scroll.y + scroll.height && CatalogService.supports(tile.model)
						&& !CatalogService.isLoaded(tile.model) && !CatalogService.isLoading(tile.model) && !CatalogService.hasFailed(tile.model)) {
					CatalogService.load(tile.model);
					started++;
				}
			}
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		opening = AstolfoUi.animate(opening, closing ? 0 : 1, 16, surface.delta);
		if (closing && opening < 0.01f) { super.onClose(); return; }
		transition = AstolfoUi.animate(transition, 1, 16, surface.delta);
		float ease = 1 - (float) Math.pow(1 - opening, 3);
		if (!surface.isCapturing()) menuScale = VisualPreferences.guiScale;
		scale = Math.min(2f / minecraft.getWindow().getGuiScale() * menuScale, Math.min((width - 16f) / W, (height - 16f) / H)) * (0.96f + ease * 0.04f);
		originX = (width - W * scale) / 2;
		originY = (height - H * scale) / 2;
		surface.begin(graphics, (mouseX - originX) / scale, (mouseY - originY) / scale);
		surface.viewWidth = W;
		surface.viewHeight = H;
		graphics.fill(0, 0, width, height, AstolfoUi.opacity(0x8C000000, opening * VisualPreferences.opacity));
		graphics.pose().pushMatrix();
		graphics.pose().translate(originX, originY);
		graphics.pose().scale(scale, scale);
		AstolfoUi.alpha = opening;
		drawWindow(graphics);
		drawSidebar(graphics);
		drawTopBar(graphics);
		drawContent(graphics);
		drawToast(graphics);
		surface.renderPopup();
		AstolfoUi.alpha = 1;
		graphics.pose().popMatrix();
	}

	private void drawWindow(GuiGraphicsExtractor graphics) {
		AstolfoUi.shadow(graphics, 0, 0, W, H, 16);
		AstolfoUi.glass(graphics, 0, 0, W, H, 16, VisualPreferences.blur, Theme.WINDOW_TINT);
		AstolfoUi.shape(graphics, 0, 0, SIDE, H, 16, 0, 0x1F000000, 0x1F000000);
		AstolfoUi.shape(graphics, SIDE - 16, 0, 16, H, 0, 0, 0x1F000000, 0x1F000000);
		AstolfoUi.shape(graphics, SIDE, 12, 0.6f, H - 24, 0, 0, Theme.SEPARATOR, Theme.SEPARATOR);
		AstolfoUi.shape(graphics, 0, 0, W, H, 16, 0.8f, 0x2EFFFFFF, 0x0DFFFFFF);
	}

	private void drawSidebar(GuiGraphicsExtractor graphics) {
		AstolfoUi.shape(graphics, 18, 20, 26, 26, 8, 0, Theme.accent(), AstolfoUi.mix(Theme.accent(), 0xFF000000, 0.25f));
		AstolfoUi.shape(graphics, 18, 20, 26, 26, 8, 0.8f, 0x55FFFFFF, 0x10FFFFFF);
		int onAccent = AstolfoUi.luminance(Theme.accent()) > 0.6f ? 0xFF0B0B0E : 0xFFFFFFFF;
		AstolfoUi.BOLD.draw(graphics, "M", 31 - AstolfoUi.BOLD.width("M", 13) / 2, 26.5f, 13, onAccent);
		AstolfoUi.BOLD.draw(graphics, "ModelMirage", 52, 22, 11, Theme.TEXT);
		AstolfoUi.MEDIUM.draw(graphics, "Loadout & Visuals", 52, 35, Theme.CAPTION, Theme.TEXT_TERTIARY);
		for (Nav nav : navigation) if (nav.page == page) navY = navY < 0 ? nav.y : AstolfoUi.animate(navY, nav.y, 20, surface.delta);
		AstolfoUi.shape(graphics, 10, navY, SIDE - 20, 24, 7, 0, 0x1FFFFFFF, 0x17FFFFFF);
		AstolfoUi.shape(graphics, 10, navY + 6, 2.5f, 12, 1.25f, 0, Theme.accent(), Theme.accent());
		for (Nav nav : navigation) nav.render(surface);
		float cardY = H - 52;
		AstolfoUi.shape(graphics, 10, cardY, SIDE - 20, 40, 10, 0, Theme.CARD, Theme.CARD);
		AstolfoUi.head(graphics, minecraft.player.getSkin().body().texturePath(), 18, Math.round(cardY + 9), 22, true, 0xFFFFFFFF);
		AstolfoUi.SEMIBOLD.draw(graphics, AstolfoUi.SEMIBOLD.fit(minecraft.player.getName().getString(), Theme.BODY, 82), 47, cardY + 10, Theme.BODY, Theme.TEXT);
		int dot = CatalogService.loading() ? Theme.accent() : CatalogService.cached() ? Theme.WARNING : CatalogService.error().isEmpty() ? Theme.SUCCESS : Theme.DANGER;
		AstolfoUi.shape(graphics, 47, cardY + 25, 4, 4, 2, 0, dot, dot);
		AstolfoUi.MEDIUM.draw(graphics, "Каталог: " + catalogStatus().toLowerCase(Locale.ROOT), 54, cardY + 23.5f, 6.5f, Theme.TEXT_TERTIARY);
		refresh.layout(SIDE - 36, cardY + 9, 22);
		refresh.render(surface);
	}

	private void drawTopBar(GuiGraphicsExtractor graphics) {
		float left = SIDE + PAD + 4;
		crumb.layout(left, 14, 300);
		crumb.render(surface);
		close.layout(W - PAD - 22, 15, 22);
		close.height = 22;
		close.render(surface);
		float cx = close.x + 11, cy = close.y + 11;
		graphics.pose().pushMatrix();
		graphics.pose().translate(cx, cy);
		graphics.pose().rotate((float) Math.toRadians(45));
		AstolfoUi.shape(graphics, -5, -0.65f, 10, 1.3f, 0.65f, 0, Theme.TEXT_SECONDARY, Theme.TEXT_SECONDARY);
		AstolfoUi.shape(graphics, -0.65f, -5, 1.3f, 10, 0.65f, 0, Theme.TEXT_SECONDARY, Theme.TEXT_SECONDARY);
		graphics.pose().popMatrix();
		search.layout(W - PAD - 30 - 168, 15, 168);
		search.render(surface);
		editor.visible = page == Page.HUD;
		if (editor.visible) {
			float w = editor.preferredWidth();
			editor.layout(search.x - w - 8, 15, w);
			editor.render(surface);
		}
		AstolfoUi.shape(graphics, SIDE + 12, TOP, W - SIDE - 24, 0.6f, 0, 0, Theme.SEPARATOR, Theme.SEPARATOR);
	}

	private void drawContent(GuiGraphicsExtractor graphics) {
		float top = TOP + 10, bottom = H - PAD, left = SIDE + PAD;
		float right = page.loadout() ? W - PAD - INSPECTOR - 2 : W - PAD + 4;
		float slide = (1 - transition) * 8;
		float previous = AstolfoUi.alpha;
		AstolfoUi.alpha = previous * (0.3f + 0.7f * transition);
		scroll.padding = 0;
		scroll.place(left, top, right - left, bottom - top, surface.delta);
		if (slide > 0.05f) scroll.content.layout(scroll.content.x, scroll.content.y + slide, scroll.content.width);
		if (grid != null) { grid.viewTop = top; grid.viewBottom = bottom; }
		for (ModelTile tile : grid == null ? List.<ModelTile>of() : grid.tiles()) if (tile.selected.getAsBoolean()) { tile.yaw = previewYaw; tile.pitch = previewPitch; }
		scroll.render(surface);
		if (grid != null && grid.isEmpty()) drawEmpty(graphics, left, top, right - left);
		AstolfoUi.alpha = previous;
		inspector.visible = page.loadout();
		if (inspector.visible) {
			inspector.layout(W - PAD - INSPECTOR, top, INSPECTOR);
			inspector.height = bottom - top;
			inspector.render(surface);
		}
	}

	private void drawEmpty(GuiGraphicsExtractor graphics, float left, float top, float width) {
		String title, hint;
		if (CatalogService.loading()) { title = "Загрузка каталога..."; hint = "Модели появятся через мгновение"; }
		else if (!query().isEmpty()) { title = "Ничего не найдено"; hint = "Попробуй другой запрос"; }
		else if (page == Page.ITEMS) { title = "Для этого предмета пока нет моделей"; hint = "Добавь модель в каталог, она появится здесь"; }
		else if (page.kind == CatalogModel.Kind.PLAYER) { title = "Моделей игроков пока нет"; hint = "Добавь модель kind: player в каталог"; }
		else { title = "Каталог пуст"; hint = "Проверь подключение и нажми обновить"; }
		float cx = left + width / 2, cy = top + 110;
		AstolfoUi.shape(graphics, cx - 20, cy - 46, 40, 40, 12, 0, Theme.CARD, Theme.CARD);
		AstolfoUi.ICONS.draw(graphics, page.icon, cx - 8, cy - 34, 16, Theme.TEXT_TERTIARY);
		AstolfoUi.SEMIBOLD.draw(graphics, title, cx - AstolfoUi.SEMIBOLD.width(title, 10) / 2, cy + 4, 10, Theme.TEXT);
		AstolfoUi.MEDIUM.draw(graphics, hint, cx - AstolfoUi.MEDIUM.width(hint, Theme.BODY) / 2, cy + 20, Theme.BODY, Theme.TEXT_SECONDARY);
	}

	private void drawToast(GuiGraphicsExtractor graphics) {
		if (toast.isEmpty()) return;
		float age = (System.currentTimeMillis() - toastTime) / 1000f;
		float fade = Math.clamp(Math.min(age * 6, (2.6f - age) * 3), 0, 1);
		if (age > 2.6f) { toast = ""; return; }
		float previous = AstolfoUi.alpha;
		AstolfoUi.alpha = previous * fade;
		float w = AstolfoUi.SEMIBOLD.width(toast, Theme.BODY) + 34, x = SIDE + (W - SIDE - w) / 2, y = H - 40 + (1 - fade) * 8;
		AstolfoUi.shadow(graphics, x, y, w, 24, 12);
		AstolfoUi.glass(graphics, x, y, w, 24, 12, 14, Theme.POPUP_TINT);
		AstolfoUi.shape(graphics, x, y, w, 24, 12, 0.8f, 0x26FFFFFF, 0x0AFFFFFF);
		int dot = toastError ? Theme.DANGER : Theme.SUCCESS;
		AstolfoUi.shape(graphics, x + 12, y + 10, 4, 4, 2, 0, dot, dot);
		AstolfoUi.SEMIBOLD.draw(graphics, toast, x + 22, Paint.centeredTextY(y, 24, Theme.BODY), Theme.BODY, Theme.TEXT);
		AstolfoUi.alpha = previous;
	}

	private void drawPlayer(GuiGraphicsExtractor graphics, boolean others, float x0, float y0, float x1, float y1) {
		var player = others ? minecraft.level.players().stream().filter(candidate -> candidate != minecraft.player).findFirst().orElse(minecraft.player) : minecraft.player;
		var renderer = minecraft.getEntityRenderDispatcher().getRenderer(player);
		AvatarRenderState state = (AvatarRenderState) renderer.createRenderState(player, 1);
		state.shadowPieces.clear();
		state.outlineColor = 0;
		state.nameTag = null;
		if (others && player == minecraft.player) { state.id = -1; state.skin = DefaultPlayerSkin.getDefaultSkin(); }
		float rotation = (float) Math.atan(((x0 + x1) / 2f - surface.mouseX) / 100) * 22 + previewYaw * 57.3f;
		state.bodyRot = 180 + rotation;
		state.yRot = Math.clamp(rotation, -40, 40);
		state.xRot = 0;
		state.scale = 1;
		Quaternionf facing = new Quaternionf().rotateZ((float) Math.PI);
		// Entity previews do not capture graphics.pose() in 26.2, so the bounds are mapped by hand.
		Vector2f topLeft = graphics.pose().transformPosition(x0, y0, new Vector2f());
		Vector2f bottomRight = graphics.pose().transformPosition(x1, y1, new Vector2f());
		graphics.enableScissor(Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1));
		graphics.entity(state, 72 * graphics.pose().m00(), new Vector3f(0, state.boundingBoxHeight / 2f, 0), facing, null,
				Math.round(topLeft.x), Math.round(topLeft.y), Math.round(bottomRight.x), Math.round(bottomRight.y));
		graphics.disableScissor();
	}

	@Override
	public void onClose() {
		if (preferencesDirty) savePreferences();
		if (dirty) { save(); if (dirty) return; }
		if (VisualPreferences.animations) closing = true;
		else super.onClose();
	}

	@Override
	public void removed() {
		// Also reached when another screen replaces the menu without onClose (HUD editor, disconnect).
		if (preferencesDirty) savePreferences();
		if (dirty) save();
	}

	private MouseButtonEvent local(MouseButtonEvent event) { return new MouseButtonEvent((event.x() - originX) / scale, (event.y() - originY) / scale, event.buttonInfo()); }

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (closing) return true;
		MouseButtonEvent mapped = local(event);
		boolean handled = surface.mouseClicked(roots, mapped.x(), mapped.y(), mapped.button());
		if (!handled && event.button() == 3 && page == Page.ITEMS && selectedItem != null) { back(); return true; }
		return handled;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		MouseButtonEvent mapped = local(event);
		return surface.mouseDragged(mapped.x(), mapped.y(), mapped.button());
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		MouseButtonEvent mapped = local(event);
		return surface.mouseReleased(mapped.x(), mapped.y(), mapped.button());
	}

	@Override
	public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		return surface.mouseScrolled(roots, (x - originX) / scale, (y - originY) / scale, vertical);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (closing) return true;
		if (surface.keyPressed(event)) return true;
		if (event.hasControlDown() && event.key() == InputConstants.KEY_F) { surface.focus(search); return true; }
		if (event.isEscape() && page == Page.ITEMS && selectedItem != null) { back(); return true; }
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (surface.charTyped(event)) return true;
		// Typing anywhere starts a search, like Spotlight.
		if (!surface.hasPopup() && event.isAllowedChatCharacter() && !Character.isWhitespace(event.codepoint())) {
			surface.focus(search);
			return search.charTyped(surface, event);
		}
		return false;
	}

	private static Identifier itemId(Item item) { return BuiltInRegistries.ITEM.getKey(item); }
	private static String itemName(Item item) { return Component.translatable(item.getDescriptionId()).getString(); }

	private enum Section {
		LOADOUT("Loadout"), VISUALS("Визуалы"), SYSTEM("Система");
		final String label;
		Section(String label) { this.label = label; }
	}

	private enum Page {
		ITEMS("Предметы", "E", Section.LOADOUT, CatalogModel.Kind.ITEM, null),
		SELF("Своя модель", "L", Section.LOADOUT, CatalogModel.Kind.PLAYER, CosmeticSelections.Slot.SELF),
		OTHERS("Другие игроки", "L", Section.LOADOUT, CatalogModel.Kind.PLAYER, CosmeticSelections.Slot.OTHERS),
		MASCOTS("Персонажи", "K", Section.LOADOUT, CatalogModel.Kind.MASCOT, CosmeticSelections.Slot.MASCOT),
		HUD("HUD", "G", Section.VISUALS, null, null),
		SCREEN("Экран", "F", Section.VISUALS, null, null),
		APPLIED("Применённые", "D", Section.SYSTEM, null, null),
		SETTINGS("Настройки", "J", Section.SYSTEM, null, null);
		final String label, icon;
		final Section section;
		final CatalogModel.Kind kind;
		final CosmeticSelections.Slot slot;
		Page(String label, String icon, Section section, CatalogModel.Kind kind, CosmeticSelections.Slot slot) {
			this.label = label; this.icon = icon; this.section = section; this.kind = kind; this.slot = slot;
		}
		boolean loadout() { return kind != null; }
	}

	/** Sidebar entry, or a section caption when {@code page} is null. */
	private final class Nav extends Control {
		final Page page;
		final Section section;

		Nav(Page page, Section section, float y) {
			this.page = page;
			this.section = section;
			this.x = 10;
			this.y = y;
			this.width = SIDE - 20;
			this.height = page == null ? 18 : 24;
		}

		@Override
		public void render(Surface surface) {
			var graphics = surface.graphics;
			if (page == null) {
				AstolfoUi.SEMIBOLD.draw(graphics, section.label.toUpperCase(Locale.ROOT), x + 8, y + 7, 6.5f, Theme.TEXT_TERTIARY);
				return;
			}
			updateHover(surface);
			boolean current = ModelMirageScreen.this.page == page;
			if (!current && hover > 0.01f) AstolfoUi.shape(graphics, x, y, width, height, 7, 0, AstolfoUi.opacity(Theme.HOVER, hover), AstolfoUi.opacity(Theme.HOVER, hover));
			int color = current ? Theme.TEXT : AstolfoUi.mix(Theme.TEXT_SECONDARY, Theme.TEXT, hover * 0.6f);
			AstolfoUi.ICONS.draw(graphics, page.icon, x + 12, y + 7, 10, current ? Theme.accent() : color);
			AstolfoUi.SEMIBOLD.draw(graphics, page.label, x + 30, Paint.centeredTextY(y, height, Theme.BODY), Theme.BODY, color);
			String badge = switch (page) {
				case APPLIED -> {
					int count = ItemModelOverrides.count();
					for (CosmeticSelections.Slot slot : CosmeticSelections.Slot.values()) if (CosmeticSelections.selected(slot) != null) count++;
					yield count == 0 ? "" : String.valueOf(count);
				}
				default -> "";
			};
			if (!badge.isEmpty()) Paint.chip(graphics, badge, x + width - 8, y + height / 2, 0x1FFFFFFF, Theme.TEXT_SECONDARY);
		}

		@Override
		public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) {
			if (page == null || button != 0 || !contains(mouseX, mouseY)) return false;
			show(page);
			return true;
		}
	}

	/** Page title; inside an item it becomes "Предметы › Item" with the first part clickable. */
	private final class Crumb extends Control {
		private float backHover;

		@Override
		public void render(Surface surface) {
			var graphics = surface.graphics;
			height = 24;
			float cy = y + 12;
			if (page == Page.ITEMS && selectedItem != null) {
				float backWidth = AstolfoUi.BOLD.width(page.label, Theme.TITLE) + 14;
				boolean over = surface.mouseX >= x && surface.mouseX < x + backWidth && surface.mouseY >= y && surface.mouseY < y + height;
				backHover = AstolfoUi.animate(backHover, over ? 1 : 0, 18, surface.delta);
				graphics.pose().pushMatrix();
				graphics.pose().translate(x + 4, cy);
				graphics.pose().rotate((float) Math.PI / 2);
				graphics.pose().translate(-x - 4, -cy);
				Paint.chevron(graphics, x + 4, cy, 0, AstolfoUi.mix(Theme.TEXT_SECONDARY, Theme.accent(), backHover));
				graphics.pose().popMatrix();
				AstolfoUi.BOLD.draw(graphics, page.label, x + 12, cy - Theme.TITLE / 2 - 0.5f, Theme.TITLE, AstolfoUi.mix(Theme.TEXT_SECONDARY, Theme.accent(), backHover));
				float after = x + backWidth + 4;
				AstolfoUi.BOLD.draw(graphics, "/", after, cy - Theme.TITLE / 2 - 0.5f, Theme.TITLE, Theme.TEXT_TERTIARY);
				String name = AstolfoUi.BOLD.fit(itemName(selectedItem), Theme.TITLE, 200);
				AstolfoUi.BOLD.draw(graphics, name, after + 12, cy - Theme.TITLE / 2 - 0.5f, Theme.TITLE, Theme.TEXT);
				width = after + 12 + AstolfoUi.BOLD.width(name, Theme.TITLE) - x;
			} else {
				AstolfoUi.BOLD.draw(graphics, page.label, x, cy - Theme.TITLE / 2 - 0.5f, Theme.TITLE, Theme.TEXT);
				width = AstolfoUi.BOLD.width(page.label, Theme.TITLE);
			}
		}

		@Override
		public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) {
			if (button != 0 || page != Page.ITEMS || selectedItem == null || !contains(mouseX, mouseY)) return false;
			if (mouseX < x + AstolfoUi.BOLD.width(page.label, Theme.TITLE) + 14) { back(); return true; }
			return false;
		}
	}

	/** Applied loadout entry with an icon, the model name and a reset button. */
	private static final class AppliedRow extends Row {
		private final ItemStack icon;
		private final String model;
		private final Button reset;

		AppliedRow(ItemStack icon, String label, String model, Runnable onReset) {
			super(label);
			this.icon = icon;
			this.model = model;
			height = 30;
			reset = new Button("Сбросить", Button.Style.DANGER, onReset);
			reset.height = 18;
			reset.textSize = Theme.CAPTION;
		}

		@Override
		public void render(Surface surface) {
			updateHover(surface);
			var graphics = surface.graphics;
			if (hover > 0.01f) AstolfoUi.shape(graphics, x + 4, y + 1, width - 8, height - 2, 6, 0, AstolfoUi.opacity(Theme.HOVER, hover), AstolfoUi.opacity(Theme.HOVER, hover));
			float textX = x + 12;
			if (!icon.isEmpty()) { graphics.item(icon, Math.round(x + 10), Math.round(y + 7)); textX += 22; }
			AstolfoUi.SEMIBOLD.draw(graphics, AstolfoUi.SEMIBOLD.fit(label, Theme.BODY, width * 0.55f), textX, y + 6, Theme.BODY, Theme.TEXT);
			AstolfoUi.MEDIUM.draw(graphics, AstolfoUi.MEDIUM.fit(model, Theme.CAPTION, width * 0.55f), textX, y + 17, Theme.CAPTION, Theme.TEXT_SECONDARY);
			renderControl(surface);
		}

		@Override
		protected void renderControl(Surface surface) {
			float w = reset.preferredWidth();
			reset.layout(right() - w, centerY() - 9, w);
			reset.render(surface);
		}

		@Override
		public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) {
			return contains(mouseX, mouseY) && reset.mouseClicked(surface, mouseX, mouseY, button);
		}
	}

	/** Right-hand card on loadout pages: live preview, selection details and actions. */
	private final class Inspector extends Control {
		private final Button apply = new Button("Применить", Button.Style.PRIMARY, ModelMirageScreen.this::applyOrRetry);
		private final Button reset = new Button("Сброс", Button.Style.SECONDARY, ModelMirageScreen.this::resetSelection);
		private final Group mascot = new Group()
				.add(new ToggleRow("Показывать в HUD", () -> Visuals.enabled("BoyKisser"), value -> { Visuals.module("BoyKisser").enabled = value; changed(); }))
				.add(new KeyRow("Клавиша", () -> Visuals.module("BoyKisser").bind, key -> { Visuals.module("BoyKisser").bind = key; changed(); }))
				.add(new ButtonRow("Расположение", "Изменить", ModelMirageScreen.this::openEditor));
		private boolean rotating;
		private float previewTop, previewHeight;

		Inspector() {
			apply.enabled = () -> selectedModel != null && !downloading && CatalogService.supports(selectedModel) && (failed || CatalogService.isLoaded(selectedModel)) && !isApplied(selectedModel);
			reset.enabled = ModelMirageScreen.this::canReset;
		}

		@Override
		public void render(Surface surface) {
			var graphics = surface.graphics;
			AstolfoUi.shape(graphics, x, y, width, height, Theme.RADIUS_CARD + 2, 0, Theme.CARD, Theme.CARD);
			AstolfoUi.shape(graphics, x, y, width, height, Theme.RADIUS_CARD + 2, 0.8f, Theme.CARD_STROKE, 0x0AFFFFFF);
			AstolfoUi.SEMIBOLD.draw(graphics, "ПРЕДПРОСМОТР", x + 12, y + 11, Theme.CAPTION, Theme.TEXT_TERTIARY);
			if (selectedModel != null && CatalogService.isLoaded(selectedModel) && page != Page.MASCOTS)
				AstolfoUi.MEDIUM.draw(graphics, "Тяни, чтобы вращать", x + width - 12 - AstolfoUi.MEDIUM.width("Тяни, чтобы вращать", 6.5f), y + 11.5f, 6.5f, Theme.TEXT_TERTIARY);
			previewTop = y + 26;
			previewHeight = page == Page.MASCOTS ? 150 : 196;
			float px = x + 8, pw = width - 16;
			AstolfoUi.shape(graphics, px, previewTop, pw, previewHeight, 9, 0, 0x0FFFFFFF, 0x05FFFFFF);
			drawPreview(graphics, px, previewTop, pw, previewHeight);
			float textY = previewTop + previewHeight + 10;
			String title = selectedModel != null ? selectedModel.name() : page == Page.ITEMS ? selectedItem == null ? "Выбери предмет" : itemName(selectedItem) : "Обычный вид";
			AstolfoUi.BOLD.draw(graphics, AstolfoUi.BOLD.fit(title, 10.5f, width - 24), x + 12, textY, 10.5f, Theme.TEXT);
			String subtitle = subtitle();
			AstolfoUi.MEDIUM.draw(graphics, AstolfoUi.MEDIUM.fit(subtitle, Theme.CAPTION, width - 24), x + 12, textY + 15, Theme.CAPTION, failed ? Theme.DANGER : Theme.TEXT_SECONDARY);
			float buttonsY = y + height - 38;
			if (page == Page.MASCOTS) {
				mascot.layout(x + 6, textY + 28, width - 12);
				mascot.render(surface);
			}
			float half = (width - 30) / 2;
			apply.text = failed ? "Повторить" : downloading ? "Загрузка..." : selectedModel != null && isApplied(selectedModel) ? "Применено" : "Применить";
			apply.height = reset.height = 26;
			apply.layout(x + 12, buttonsY, half);
			reset.layout(x + 18 + half, buttonsY, half);
			apply.render(surface);
			reset.render(surface);
		}

		private String subtitle() {
			if (selectedModel == null) {
				if (page == Page.ITEMS && selectedItem == null) return "Слева сетка всех предметов";
				Identifier applied = appliedId();
				return applied == null ? page == Page.ITEMS ? itemId(selectedItem).toString() : "Модель не выбрана" : "Сейчас: " + modelName(applied);
			}
			if (failed) return "Не удалось загрузить";
			if (downloading) return "Загрузка модели...";
			if (isApplied(selectedModel)) return "Применено, " + selectedModel.group();
			return selectedModel.group() + ", " + selectedModel.format();
		}

		private void drawPreview(GuiGraphicsExtractor graphics, float px, float py, float pw, float ph) {
			graphics.enableScissor(Math.round(px), Math.round(py), Math.round(px + pw), Math.round(py + ph));
			float cx = px + pw / 2, cy = py + ph / 2;
			if (page == Page.MASCOTS) {
				Identifier preview = selectedModel != null && CatalogService.isLoaded(selectedModel) ? selectedModel.modelId() : CosmeticSelections.selected(CosmeticSelections.Slot.MASCOT);
				float ratio = Math.min((pw - 40) / MascotAnimations.width(preview), (ph - 24) / MascotAnimations.height(preview));
				int w = Math.round(MascotAnimations.width(preview) * ratio), h = Math.round(MascotAnimations.height(preview) * ratio);
				MascotAnimations.draw(graphics, preview, Math.round(cx - w / 2f), Math.round(cy - h / 2f), w, h, AstolfoUi.opacity(0xFFFFFFFF, AstolfoUi.alpha));
			} else if (selectedModel != null && CatalogService.isLoaded(selectedModel) && !(page.slot != null && isApplied(selectedModel))) {
				RuntimeModels.preview(graphics, selectedModel.modelId(), cx, cy, 120, previewYaw, previewPitch);
			} else if (page.slot != null) {
				graphics.disableScissor();
				drawPlayer(graphics, page == Page.OTHERS, px, py + 4, px + pw, py + ph - 4);
				return;
			} else if (selectedItem != null) {
				graphics.pose().pushMatrix();
				graphics.pose().translate(cx - 40, cy - 40);
				graphics.pose().scale(5, 5);
				graphics.item(new ItemStack(selectedItem), 0, 0);
				graphics.pose().popMatrix();
			} else {
				AstolfoUi.ICONS.draw(graphics, "E", cx - 12, cy - 18, 24, Theme.TEXT_TERTIARY);
				String hint = "Предмет не выбран";
				AstolfoUi.MEDIUM.draw(graphics, hint, cx - AstolfoUi.MEDIUM.width(hint, Theme.BODY) / 2, cy + 12, Theme.BODY, Theme.TEXT_TERTIARY);
			}
			graphics.disableScissor();
		}

		@Override
		public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) {
			if (!visible || !contains(mouseX, mouseY)) return false;
			if (apply.mouseClicked(surface, mouseX, mouseY, button) || reset.mouseClicked(surface, mouseX, mouseY, button)) return true;
			if (page == Page.MASCOTS && mascot.mouseClicked(surface, mouseX, mouseY, button)) return true;
			if (button == 0 && mouseY >= previewTop && mouseY < previewTop + previewHeight && page != Page.MASCOTS) {
				rotating = true;
				surface.capture(this);
				lastX = mouseX;
				lastY = mouseY;
			}
			return true;
		}

		private double lastX, lastY;

		@Override
		public boolean mouseDragged(Surface surface, double mouseX, double mouseY, int button) {
			if (!rotating) return false;
			previewYaw += (float) ((mouseX - lastX) * 0.025);
			previewPitch = Math.clamp(previewPitch + (float) ((mouseY - lastY) * 0.025), -1.3f, 1.3f);
			lastX = mouseX;
			lastY = mouseY;
			return true;
		}

		@Override
		public void mouseReleased(Surface surface, double mouseX, double mouseY, int button) { rotating = false; }
	}
}
