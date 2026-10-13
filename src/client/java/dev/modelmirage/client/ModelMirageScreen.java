package dev.modelmirage.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.modelmirage.client.catalog.CatalogModel;
import dev.modelmirage.client.catalog.CatalogService;
import dev.modelmirage.client.gui.MirageUi;
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
import dev.modelmirage.client.gui.kit.SliderRow;
import dev.modelmirage.client.gui.kit.Surface;
import dev.modelmirage.client.gui.kit.Theme;
import dev.modelmirage.client.gui.kit.ToggleRow;
import dev.modelmirage.client.render.MascotAnimations;
import dev.modelmirage.client.render.RuntimeModels;
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
	private final SearchField search = new SearchField(Lang.tr("modelmirage.search.placeholder"), value -> { scroll.reset(); rebuild(); });
	private Button close, refresh;
	private Crumb crumb;
	private TileGrid grid;
	private List<CatalogModel> catalog = List.of();
	private Page page = Page.ITEMS;
	private Item selectedItem;
	private CatalogModel selectedModel;
	private boolean dirty, preferencesDirty, closing, downloading, failed, closeUnsaved;
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
		// The controls below are rebuilt; focus, drags and popups must not keep pointing at the old ones.
		surface.reset();
		navigation.clear();
		roots.clear();
		close = new Button("", Button.Style.PLAIN, this::onClose);
		refresh = new Button("", Button.Style.PLAIN, this::refreshCatalog);
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
		roots.add(refresh);
		roots.add(crumb);
		roots.add(close);
		catalog = CatalogService.models();
		// init() also runs on resize: keep an unapplied selection.
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

	private void refreshCatalog() {
		if (CatalogService.loading()) { notify(Lang.tr("modelmirage.toast.catalog_refreshing"), false); return; }
		notify(Lang.tr("modelmirage.toast.catalog_refresh"), false);
		CatalogService.refresh().whenComplete((unused, failure) -> minecraft.execute(() -> {
			if (!CatalogService.error().isEmpty()) notify(Lang.tr(CatalogService.error()), true);
			else if (CatalogService.cached()) notify(Lang.tr("modelmirage.toast.catalog_cached"), true);
			else notify(Lang.tr("modelmirage.toast.catalog_updated", CatalogService.models().size() - 1), false);
		}));
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
				grid.sections.add(new TileGrid.Section(Lang.tr("modelmirage.items.weapons"), weapons));
				grid.sections.add(new TileGrid.Section(Lang.tr("modelmirage.items.tools"), tools));
				grid.sections.add(new TileGrid.Section(Lang.tr("modelmirage.items.other"), other));
			} else {
				List<ModelTile> tiles = new ArrayList<>();
				for (CatalogModel entry : catalog) {
					if (!fits(entry)) continue;
					if (!(entry.name() + " " + entry.id() + " " + ModelTile.groupName(entry)).toLowerCase(Locale.ROOT).contains(query)) continue;
					ModelTile tile = new ModelTile(entry, () -> selectModel(entry));
					tile.selected = () -> entry.equals(selectedModel);
					tile.applied = () -> isApplied(entry);
					tiles.add(tile);
				}
				grid.sections.add(new TileGrid.Section(selectedItem == null ? "" : Lang.tr("modelmirage.items.models_for_item"), tiles));
			}
			scroll.content = grid;
			return;
		}
		Columns columns = new Columns();
		switch (page) {
			case APPLIED -> buildApplied(columns, query);
			case SETTINGS -> buildSettings(columns, query);
			default -> throw new IllegalStateException("Unexpected page " + page);
		}
		scroll.content = columns;
	}

	private void buildApplied(Columns columns, String query) {
		Group items = new Group().caption("E", Lang.tr("modelmirage.page.items")).title(Lang.tr("modelmirage.applied.item_models"));
		ItemModelOverrides.all().forEach((itemKey, model) -> {
			Item item = BuiltInRegistries.ITEM.getValue(itemKey);
			String name = item == Items.AIR ? itemKey.toString() : itemName(item);
			if (!(name + " " + itemKey + " " + modelName(model)).toLowerCase(Locale.ROOT).contains(query)) return;
			items.add(new AppliedRow(new ItemStack(item), name, modelName(model), () -> {
				try { ItemModelOverrides.setModel(itemKey, null); notify(Lang.tr("modelmirage.toast.reset", name), false); rebuild(); }
				catch (IOException failure) { saveFailed("Could not reset item override", failure); }
			}));
		});
		if (items.rows.isEmpty()) items.add(new InfoRow(query.isEmpty() ? Lang.tr("modelmirage.applied.nothing") : Lang.tr("modelmirage.state.nothing_found"), () -> ""));
		columns.children.add(items);
		Group players = new Group().caption("L", Lang.tr("modelmirage.page.mascots")).title(Lang.tr("modelmirage.applied.players_hud"));
		for (CosmeticSelections.Slot slot : CosmeticSelections.Slot.values()) {
			Identifier selected = CosmeticSelections.selected(slot);
			String label = switch (slot) { case SELF -> Lang.tr("modelmirage.page.self"); case OTHERS -> Lang.tr("modelmirage.page.others"); case MASCOT -> Lang.tr("modelmirage.module.Mascot"); };
			if (!CosmeticSelections.isSet(slot)) players.add(new InfoRow(label, () -> slot == CosmeticSelections.Slot.MASCOT ? CatalogModel.builtinMascot().name() : Lang.tr("modelmirage.applied.default")));
			else players.add(new AppliedRow(ItemStack.EMPTY, label, modelName(selected), () -> {
				try { CosmeticSelections.set(slot, null); notify(Lang.tr("modelmirage.toast.reset", label), false); rebuild(); }
				catch (IOException failure) { saveFailed("Could not reset cosmetic", failure); }
			}));
		}
		columns.children.add(players);
	}

	private void buildSettings(Columns columns, String query) {
		Group look = new Group().caption("J", Lang.tr("modelmirage.settings.appearance")).title(Lang.tr("modelmirage.settings.look"));
		look.add(new ColorRow(Lang.tr("modelmirage.settings.accent"), () -> MirageUi.ACCENT, color -> { MirageUi.ACCENT = color; preferencesChanged(); }));
		look.add(new SliderRow(Lang.tr("modelmirage.settings.menu_scale"), 0.75, 1.5, 0.05, "x", () -> VisualPreferences.guiScale, value -> { VisualPreferences.guiScale = (float) value; preferencesChanged(); }));
		look.add(new ToggleRow(Lang.tr("modelmirage.settings.animations"), () -> VisualPreferences.animations, value -> { VisualPreferences.animations = value; preferencesChanged(); }));
		Group hud = new Group().caption("G", Lang.tr("modelmirage.settings.hud")).title(Lang.tr("modelmirage.settings.hud_title"))
				.toggle(() -> VisualPreferences.hudEnabled, value -> { VisualPreferences.hudEnabled = value; preferencesChanged(); });
		hud.add(new SliderRow(Lang.tr("modelmirage.settings.hud_scale"), 0.5, 1.5, 0.05, "x", () -> VisualPreferences.hudScale, value -> { VisualPreferences.hudScale = (float) value; preferencesChanged(); }));
		hud.add(new ToggleRow(Lang.tr("modelmirage.settings.accent_icons"), () -> VisualPreferences.accentIcons, value -> { VisualPreferences.accentIcons = value; preferencesChanged(); }));
		hud.add(new ToggleRow(Lang.tr("modelmirage.settings.hud_grid"), () -> VisualPreferences.hudGrid, value -> { VisualPreferences.hudGrid = value; preferencesChanged(); }));
		hud.add(new InfoRow(Lang.tr("modelmirage.hud.panel_layout"), () -> Lang.tr("modelmirage.settings.hud_layout_hint")));
		// One glass for the menu and every HUD panel; a panel can switch to its own in its card.
		Group glass = new Group().caption("F", Lang.tr("modelmirage.settings.glass")).title(Lang.tr("modelmirage.settings.glass_title"));
		glass.add(new SliderRow(Lang.tr("modelmirage.settings.blur"), 0, 30, 1, "px", () -> VisualPreferences.blur, value -> { VisualPreferences.blur = (float) value; preferencesChanged(); }));
		glass.add(new SliderRow(Lang.tr("modelmirage.settings.tint"), 0, 90, 5, "%", () -> VisualPreferences.tint, value -> { VisualPreferences.tint = (float) value; preferencesChanged(); }));
		glass.add(new SliderRow(Lang.tr("modelmirage.settings.dim"), 0, 100, 5, "%", () -> VisualPreferences.opacity * 100, value -> { VisualPreferences.opacity = (float) value / 100; preferencesChanged(); }));
		Group source = new Group().caption("E", Lang.tr("modelmirage.settings.catalog")).title(Lang.tr("modelmirage.settings.catalog_source"));
		source.add(new InfoRow(Lang.tr("modelmirage.settings.status"), ModelMirageScreen::catalogStatus));
		source.add(new InfoRow(Lang.tr("modelmirage.settings.models"), () -> String.valueOf(CatalogService.models().size())));
		source.add(new ButtonRow(Lang.tr("modelmirage.settings.catalog"), Lang.tr("modelmirage.action.refresh"), this::refreshCatalog));
		Group about = new Group().caption("D", Lang.tr("modelmirage.settings.about")).title("ModelMirage");
		about.add(new InfoRow(Lang.tr("modelmirage.settings.version"), () -> FabricLoader.getInstance().getModContainer("modelmirage").map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("?")));
		about.add(new InfoRow(Lang.tr("modelmirage.settings.open_menu"), () -> ModelMirageClient.openKey.getTranslatedKeyMessage().getString()));
		for (Group group : List.of(look, hud, glass, source, about)) {
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
		notify(Lang.tr("modelmirage.toast.save_failed"), true);
		LoggerFactory.getLogger("ModelMirage").error(log, failure);
	}

	private String modelName(Identifier model) {
		return catalog.stream().filter(entry -> entry.modelId().equals(model)).map(CatalogModel::name).findFirst().orElse(Lang.tr("modelmirage.applied.not_in_catalog"));
	}

	private static String catalogStatus() {
		if (CatalogService.loading()) return Lang.tr("modelmirage.state.loading");
		if (CatalogService.cached()) return Lang.tr("modelmirage.settings.status.cached");
		return CatalogService.error().isEmpty() ? Lang.tr("modelmirage.settings.status.connected") : Lang.tr("modelmirage.settings.status.unavailable");
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
		failed = downloading = false;
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
		selectedModel = catalog.stream().filter(entry -> fits(entry) && entry.modelId().equals(id)).findFirst().orElse(null);
		failed = downloading = false;
		if (selectedModel != null && CatalogService.supports(selectedModel)) selectModel(selectedModel);
	}

	/** Whether the open page and item can use the model; the grid lists only these, and only these can be applied. */
	private boolean fits(CatalogModel model) {
		return model.kind() == page.kind && (selectedItem == null || model.accepts(itemId(selectedItem)));
	}

	private void selectModel(CatalogModel model) {
		selectedModel = model;
		failed = false;
		previewYaw = previewPitch = 0;
		if (!CatalogService.supports(model)) { downloading = false; notify(Lang.tr("modelmirage.toast.needs_loader", model.format()), true); return; }
		downloading = !CatalogService.isLoaded(model);
		CatalogService.load(model).whenComplete((unused, failure) -> minecraft.execute(() -> {
			if (!model.equals(selectedModel)) return;
			downloading = false;
			failed = failure != null;
			if (failed) notify(Lang.tr("modelmirage.toast.load_failed"), true);
		}));
	}

	private void applyOrRetry() {
		// A refresh may have changed the entry's kind or items since it was selected.
		if (selectedModel == null || !fits(selectedModel)) return;
		if (failed) { CatalogService.retry(selectedModel); selectModel(selectedModel); return; }
		if (!CatalogService.isLoaded(selectedModel)) return;
		try {
			if (page.slot != null) CosmeticSelections.set(page.slot, selectedModel.modelId());
			else if (selectedItem != null) ItemModelOverrides.setModel(itemId(selectedItem), selectedModel.modelId());
			else return;
			notify(Lang.tr("modelmirage.toast.applied", selectedModel.name()), false);
		} catch (IOException failure) { saveFailed("Could not apply loadout model", failure); }
	}

	private void resetSelection() {
		try {
			if (page.slot != null) CosmeticSelections.set(page.slot, null);
			else if (selectedItem != null) ItemModelOverrides.setModel(itemId(selectedItem), null);
			else return;
			notify(Lang.tr("modelmirage.toast.restored"), false);
			restoreSelectedModel();
		} catch (IOException failure) { saveFailed("Could not reset loadout", failure); }
	}

	private boolean canReset() {
		if (page.slot != null) return CosmeticSelections.isSet(page.slot);
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
				if (next == null || !fits(next)) { selectedModel = null; failed = downloading = false; }
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
		opening = MirageUi.animate(opening, closing ? 0 : 1, 16, surface.delta);
		if (closing && opening < 0.01f) { super.onClose(); return; }
		transition = MirageUi.animate(transition, 1, 16, surface.delta);
		float ease = 1 - (float) Math.pow(1 - opening, 3);
		if (!surface.isCapturing()) menuScale = VisualPreferences.guiScale;
		scale = Math.min(2f / minecraft.getWindow().getGuiScale() * menuScale, Math.min((width - 16f) / W, (height - 16f) / H)) * (0.96f + ease * 0.04f);
		originX = (width - W * scale) / 2;
		originY = (height - H * scale) / 2;
		surface.begin(graphics, (mouseX - originX) / scale, (mouseY - originY) / scale);
		surface.viewWidth = W;
		surface.viewHeight = H;
		graphics.fill(0, 0, width, height, MirageUi.opacity(0x8C000000, opening * VisualPreferences.opacity));
		graphics.pose().pushMatrix();
		graphics.pose().translate(originX, originY);
		graphics.pose().scale(scale, scale);
		MirageUi.alpha = opening;
		drawWindow(graphics);
		drawSidebar(graphics);
		drawTopBar(graphics);
		drawContent(graphics);
		drawToast(graphics);
		surface.renderPopup();
		MirageUi.alpha = 1;
		graphics.pose().popMatrix();
	}

	private void drawWindow(GuiGraphicsExtractor graphics) {
		MirageUi.shadow(graphics, 0, 0, W, H, 16);
		MirageUi.glass(graphics, 0, 0, W, H, 16, VisualPreferences.blur, Theme.WINDOW_TINT);
		MirageUi.shape(graphics, 0, 0, SIDE, H, 16, 0, 0x1F000000, 0x1F000000);
		MirageUi.shape(graphics, SIDE - 16, 0, 16, H, 0, 0, 0x1F000000, 0x1F000000);
		MirageUi.shape(graphics, SIDE, 12, 0.6f, H - 24, 0, 0, Theme.SEPARATOR, Theme.SEPARATOR);
		MirageUi.shape(graphics, 0, 0, W, H, 16, 0.8f, 0x2EFFFFFF, 0x0DFFFFFF);
	}

	private void drawSidebar(GuiGraphicsExtractor graphics) {
		MirageUi.shape(graphics, 18, 20, 26, 26, 8, 0, Theme.accent(), MirageUi.mix(Theme.accent(), 0xFF000000, 0.25f));
		MirageUi.shape(graphics, 18, 20, 26, 26, 8, 0.8f, 0x55FFFFFF, 0x10FFFFFF);
		int onAccent = MirageUi.luminance(Theme.accent()) > 0.6f ? 0xFF0B0B0E : 0xFFFFFFFF;
		MirageUi.BOLD.draw(graphics, "M", 31 - MirageUi.BOLD.width("M", 13) / 2, 26.5f, 13, onAccent);
		MirageUi.BOLD.draw(graphics, "ModelMirage", 52, 22, 11, Theme.TEXT);
		MirageUi.MEDIUM.draw(graphics, Lang.tr("modelmirage.sidebar.tagline"), 52, 35, Theme.CAPTION, Theme.TEXT_TERTIARY);
		for (Nav nav : navigation) if (nav.page == page) navY = navY < 0 ? nav.y : MirageUi.animate(navY, nav.y, 20, surface.delta);
		MirageUi.shape(graphics, 10, navY, SIDE - 20, 24, 7, 0, 0x1FFFFFFF, 0x17FFFFFF);
		MirageUi.shape(graphics, 10, navY + 6, 2.5f, 12, 1.25f, 0, Theme.accent(), Theme.accent());
		for (Nav nav : navigation) nav.render(surface);
		float cardY = H - 52;
		MirageUi.shape(graphics, 10, cardY, SIDE - 20, 40, 10, 0, Theme.CARD, Theme.CARD);
		MirageUi.head(graphics, minecraft.player.getSkin().body().texturePath(), 18, Math.round(cardY + 9), 22, true, 0xFFFFFFFF);
		MirageUi.SEMIBOLD.draw(graphics, MirageUi.SEMIBOLD.fit(minecraft.player.getName().getString(), Theme.BODY, 82), 47, cardY + 10, Theme.BODY, Theme.TEXT);
		int dot = CatalogService.loading() ? Theme.accent() : CatalogService.cached() ? Theme.WARNING : CatalogService.error().isEmpty() ? Theme.SUCCESS : Theme.DANGER;
		MirageUi.shape(graphics, 47, cardY + 25, 4, 4, 2, 0, dot, dot);
		MirageUi.MEDIUM.draw(graphics, Lang.tr("modelmirage.sidebar.catalog", catalogStatus().toLowerCase(Locale.ROOT)), 54, cardY + 23.5f, 6.5f, Theme.TEXT_TERTIARY);
		refresh.layout(SIDE - 36, cardY + 9, 22);
		refresh.render(surface);
		drawRefreshIcon(graphics, refresh.x + 11, refresh.y + 11);
	}

	/** The icon font has no reload arrow: a three-quarter ring of short segments ending in an arrowhead, spinning while loading. */
	private static void drawRefreshIcon(GuiGraphicsExtractor graphics, float cx, float cy) {
		int color = Theme.TEXT_SECONDARY;
		graphics.pose().pushMatrix();
		graphics.pose().translate(cx, cy);
		if (CatalogService.loading()) graphics.pose().rotate((float) (System.currentTimeMillis() % 900 / 900.0 * Math.PI * 2));
		// From the right edge clockwise through the bottom and left to the top; the top-right quarter stays open.
		for (int step = 0; step <= 10; step++) {
			graphics.pose().pushMatrix();
			graphics.pose().rotate((float) Math.toRadians(step * 27));
			MirageUi.shape(graphics, 3.4f, -1.2f, 1.2f, 2.4f, 0.6f, 0, color, color);
			graphics.pose().popMatrix();
		}
		Paint.chevron(graphics, 0.8f, -4, -0.5f, color);
		graphics.pose().popMatrix();
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
		MirageUi.shape(graphics, -5, -0.65f, 10, 1.3f, 0.65f, 0, Theme.TEXT_SECONDARY, Theme.TEXT_SECONDARY);
		MirageUi.shape(graphics, -0.65f, -5, 1.3f, 10, 0.65f, 0, Theme.TEXT_SECONDARY, Theme.TEXT_SECONDARY);
		graphics.pose().popMatrix();
		search.layout(W - PAD - 30 - 168, 15, 168);
		search.render(surface);
		MirageUi.shape(graphics, SIDE + 12, TOP, W - SIDE - 24, 0.6f, 0, 0, Theme.SEPARATOR, Theme.SEPARATOR);
	}

	private void drawContent(GuiGraphicsExtractor graphics) {
		float top = TOP + 10, bottom = H - PAD, left = SIDE + PAD;
		float right = page.loadout() ? W - PAD - INSPECTOR - 2 : W - PAD + 4;
		float slide = (1 - transition) * 8;
		float previous = MirageUi.alpha;
		MirageUi.alpha = previous * (0.3f + 0.7f * transition);
		scroll.place(left, top, right - left, bottom - top, surface.delta);
		if (slide > 0.05f) scroll.content.layout(scroll.content.x, scroll.content.y + slide, scroll.content.width);
		if (grid != null) { grid.viewTop = top; grid.viewBottom = bottom; }
		for (ModelTile tile : grid == null ? List.<ModelTile>of() : grid.tiles()) if (tile.selected.getAsBoolean()) { tile.yaw = previewYaw; tile.pitch = previewPitch; }
		scroll.render(surface);
		if (grid != null && grid.isEmpty()) drawEmpty(graphics, left, top, right - left);
		MirageUi.alpha = previous;
		inspector.visible = page.loadout();
		if (inspector.visible) {
			inspector.layout(W - PAD - INSPECTOR, top, INSPECTOR);
			inspector.height = bottom - top;
			inspector.render(surface);
		}
	}

	private void drawEmpty(GuiGraphicsExtractor graphics, float left, float top, float width) {
		String title, hint;
		if (CatalogService.loading()) { title = Lang.tr("modelmirage.empty.loading"); hint = Lang.tr("modelmirage.empty.loading_hint"); }
		else if (!CatalogService.error().isEmpty() && (page != Page.ITEMS || selectedItem != null)) { title = Lang.tr(CatalogService.error()); hint = Lang.tr("modelmirage.empty.unavailable_hint"); }
		else if (!query().isEmpty()) { title = Lang.tr("modelmirage.state.nothing_found"); hint = Lang.tr("modelmirage.empty.nothing_found_hint"); }
		else if (page == Page.ITEMS) { title = Lang.tr("modelmirage.empty.no_item_models"); hint = Lang.tr("modelmirage.empty.no_item_models_hint"); }
		else if (page.kind == CatalogModel.Kind.PLAYER) { title = Lang.tr("modelmirage.empty.no_players"); hint = Lang.tr("modelmirage.empty.no_players_hint"); }
		else { title = Lang.tr("modelmirage.empty.catalog_empty"); hint = Lang.tr("modelmirage.empty.catalog_empty_hint"); }
		float cx = left + width / 2, cy = top + 110;
		MirageUi.shape(graphics, cx - 20, cy - 46, 40, 40, 12, 0, Theme.CARD, Theme.CARD);
		MirageUi.ICONS.draw(graphics, page.icon, cx - 8, cy - 34, 16, Theme.TEXT_TERTIARY);
		MirageUi.SEMIBOLD.draw(graphics, title, cx - MirageUi.SEMIBOLD.width(title, 10) / 2, cy + 4, 10, Theme.TEXT);
		MirageUi.MEDIUM.draw(graphics, hint, cx - MirageUi.MEDIUM.width(hint, Theme.BODY) / 2, cy + 20, Theme.BODY, Theme.TEXT_SECONDARY);
	}

	private void drawToast(GuiGraphicsExtractor graphics) {
		if (toast.isEmpty()) return;
		float age = (System.currentTimeMillis() - toastTime) / 1000f;
		float fade = Math.clamp(Math.min(age * 6, (2.6f - age) * 3), 0, 1);
		if (age > 2.6f) { toast = ""; return; }
		float previous = MirageUi.alpha;
		MirageUi.alpha = previous * fade;
		// On loadout pages the toast centres over the grid so it never covers the inspector buttons.
		float right = page.loadout() ? W - PAD - INSPECTOR : W;
		float w = MirageUi.SEMIBOLD.width(toast, Theme.BODY) + 34, x = SIDE + (right - SIDE - w) / 2, y = H - 40 + (1 - fade) * 8;
		MirageUi.shadow(graphics, x, y, w, 24, 12);
		MirageUi.glass(graphics, x, y, w, 24, 12, 14, Theme.POPUP_TINT);
		MirageUi.shape(graphics, x, y, w, 24, 12, 0.8f, 0x26FFFFFF, 0x0AFFFFFF);
		int dot = toastError ? Theme.DANGER : Theme.SUCCESS;
		MirageUi.shape(graphics, x + 12, y + 10, 4, 4, 2, 0, dot, dot);
		MirageUi.SEMIBOLD.draw(graphics, toast, x + 22, Paint.centeredTextY(y, 24, Theme.BODY), Theme.BODY, Theme.TEXT);
		MirageUi.alpha = previous;
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
		if (dirty) {
			save();
			// A broken config must not trap the player in the menu: the second Esc closes without saving.
			if (dirty && !closeUnsaved) { closeUnsaved = true; notify(Lang.tr("modelmirage.toast.unsaved"), true); return; }
		}
		if (VisualPreferences.animations) closing = true;
		else super.onClose();
	}

	@Override
	public void removed() {
		// Also reached when another screen replaces the menu without onClose (disconnect).
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
		if (event.isConfirmation() && surface.isFocused(search) && grid != null && grid.tiles().size() == 1) {
			surface.focus(null);
			grid.tiles().getFirst().activate();
			return true;
		}
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
		LOADOUT, SYSTEM;
		String label() { return switch (this) { case LOADOUT -> Lang.tr("modelmirage.section.loadout"); case SYSTEM -> Lang.tr("modelmirage.section.system"); }; }
	}

	private enum Page {
		ITEMS("E", Section.LOADOUT, CatalogModel.Kind.ITEM, null),
		SELF("L", Section.LOADOUT, CatalogModel.Kind.PLAYER, CosmeticSelections.Slot.SELF),
		OTHERS("L", Section.LOADOUT, CatalogModel.Kind.PLAYER, CosmeticSelections.Slot.OTHERS),
		MASCOTS("K", Section.LOADOUT, CatalogModel.Kind.MASCOT, CosmeticSelections.Slot.MASCOT),
		APPLIED("D", Section.SYSTEM, null, null),
		SETTINGS("J", Section.SYSTEM, null, null);
		final String icon;
		final Section section;
		final CatalogModel.Kind kind;
		final CosmeticSelections.Slot slot;
		Page(String icon, Section section, CatalogModel.Kind kind, CosmeticSelections.Slot slot) {
			this.icon = icon; this.section = section; this.kind = kind; this.slot = slot;
		}
		boolean loadout() { return kind != null; }
		String label() {
			return switch (this) {
				case ITEMS -> Lang.tr("modelmirage.page.items");
				case SELF -> Lang.tr("modelmirage.page.self");
				case OTHERS -> Lang.tr("modelmirage.page.others");
				case MASCOTS -> Lang.tr("modelmirage.page.mascots");
				case APPLIED -> Lang.tr("modelmirage.page.applied");
				case SETTINGS -> Lang.tr("modelmirage.page.settings");
			};
		}
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
				MirageUi.SEMIBOLD.draw(graphics, section.label().toUpperCase(Locale.ROOT), x + 8, y + 7, 6.5f, Theme.TEXT_TERTIARY);
				return;
			}
			updateHover(surface);
			boolean current = ModelMirageScreen.this.page == page;
			if (!current && hover > 0.01f) MirageUi.shape(graphics, x, y, width, height, 7, 0, MirageUi.opacity(Theme.HOVER, hover), MirageUi.opacity(Theme.HOVER, hover));
			int color = current ? Theme.TEXT : MirageUi.mix(Theme.TEXT_SECONDARY, Theme.TEXT, hover * 0.6f);
			MirageUi.ICONS.draw(graphics, page.icon, x + 12, y + 7, 10, current ? Theme.accent() : color);
			MirageUi.SEMIBOLD.draw(graphics, page.label(), x + 30, Paint.centeredTextY(y, height, Theme.BODY), Theme.BODY, color);
			if (page == Page.APPLIED) {
				int count = ItemModelOverrides.count();
				for (CosmeticSelections.Slot slot : CosmeticSelections.Slot.values()) if (CosmeticSelections.isSet(slot)) count++;
				if (count > 0) Paint.chip(graphics, String.valueOf(count), x + width - 8, y + height / 2, 0x1FFFFFFF, Theme.TEXT_SECONDARY);
			}
		}

		@Override
		public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) {
			if (page == null || button != 0 || !contains(mouseX, mouseY)) return false;
			show(page);
			return true;
		}
	}

	/** Page title; inside an item it becomes "Items › Item" with the first part clickable. */
	private final class Crumb extends Control {
		private float backHover;

		@Override
		public void render(Surface surface) {
			var graphics = surface.graphics;
			height = 24;
			float cy = y + 12;
			if (page == Page.ITEMS && selectedItem != null) {
				float backWidth = MirageUi.BOLD.width(page.label(), Theme.TITLE) + 14;
				boolean over = surface.mouseX >= x && surface.mouseX < x + backWidth && surface.mouseY >= y && surface.mouseY < y + height;
				backHover = MirageUi.animate(backHover, over ? 1 : 0, 18, surface.delta);
				graphics.pose().pushMatrix();
				graphics.pose().translate(x + 4, cy);
				graphics.pose().rotate((float) Math.PI / 2);
				graphics.pose().translate(-x - 4, -cy);
				Paint.chevron(graphics, x + 4, cy, 0, MirageUi.mix(Theme.TEXT_SECONDARY, Theme.accent(), backHover));
				graphics.pose().popMatrix();
				MirageUi.BOLD.draw(graphics, page.label(), x + 12, cy - Theme.TITLE / 2 - 0.5f, Theme.TITLE, MirageUi.mix(Theme.TEXT_SECONDARY, Theme.accent(), backHover));
				float after = x + backWidth + 4;
				MirageUi.BOLD.draw(graphics, "/", after, cy - Theme.TITLE / 2 - 0.5f, Theme.TITLE, Theme.TEXT_TERTIARY);
				String name = MirageUi.BOLD.fit(itemName(selectedItem), Theme.TITLE, 200);
				MirageUi.BOLD.draw(graphics, name, after + 12, cy - Theme.TITLE / 2 - 0.5f, Theme.TITLE, Theme.TEXT);
				width = after + 12 + MirageUi.BOLD.width(name, Theme.TITLE) - x;
			} else {
				MirageUi.BOLD.draw(graphics, page.label(), x, cy - Theme.TITLE / 2 - 0.5f, Theme.TITLE, Theme.TEXT);
				width = MirageUi.BOLD.width(page.label(), Theme.TITLE);
			}
		}

		@Override
		public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) {
			if (button != 0 || page != Page.ITEMS || selectedItem == null || !contains(mouseX, mouseY)) return false;
			if (mouseX < x + MirageUi.BOLD.width(page.label(), Theme.TITLE) + 14) { back(); return true; }
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
			reset = new Button(Lang.tr("modelmirage.action.reset"), Button.Style.DANGER, onReset);
			reset.height = 18;
			reset.textSize = Theme.CAPTION;
		}

		@Override
		public void render(Surface surface) {
			updateHover(surface);
			var graphics = surface.graphics;
			if (hover > 0.01f) MirageUi.shape(graphics, x + 4, y + 1, width - 8, height - 2, 6, 0, MirageUi.opacity(Theme.HOVER, hover), MirageUi.opacity(Theme.HOVER, hover));
			float textX = x + 12;
			if (!icon.isEmpty()) { graphics.item(icon, Math.round(x + 10), Math.round(y + 7)); textX += 22; }
			MirageUi.SEMIBOLD.draw(graphics, MirageUi.SEMIBOLD.fit(label, Theme.BODY, width * 0.55f), textX, y + 6, Theme.BODY, Theme.TEXT);
			MirageUi.MEDIUM.draw(graphics, MirageUi.MEDIUM.fit(model, Theme.CAPTION, width * 0.55f), textX, y + 17, Theme.CAPTION, Theme.TEXT_SECONDARY);
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
		private final Button apply = new Button(Lang.tr("modelmirage.inspector.apply"), Button.Style.PRIMARY, ModelMirageScreen.this::applyOrRetry);
		private final Button reset = new Button(Lang.tr("modelmirage.inspector.reset"), Button.Style.SECONDARY, ModelMirageScreen.this::resetSelection);
		private final Group mascot = new Group()
				.add(new ToggleRow(Lang.tr("modelmirage.inspector.show_in_hud"), () -> Visuals.enabled("Mascot"), value -> { Visuals.module("Mascot").enabled = value; changed(); }))
				.add(new KeyRow(Lang.tr("modelmirage.row.key"), () -> Visuals.module("Mascot").bind, key -> { Visuals.module("Mascot").bind = key; changed(); }));
		private boolean rotating;
		private float previewTop, previewHeight;

		Inspector() {
			apply.enabled = () -> selectedModel != null && !downloading && CatalogService.supports(selectedModel) && (failed || CatalogService.isLoaded(selectedModel)) && !isApplied(selectedModel);
			reset.enabled = ModelMirageScreen.this::canReset;
		}

		@Override
		public void render(Surface surface) {
			var graphics = surface.graphics;
			MirageUi.shape(graphics, x, y, width, height, Theme.RADIUS_CARD + 2, 0, Theme.CARD, Theme.CARD);
			MirageUi.shape(graphics, x, y, width, height, Theme.RADIUS_CARD + 2, 0.8f, Theme.CARD_STROKE, 0x0AFFFFFF);
			MirageUi.SEMIBOLD.draw(graphics, Lang.tr("modelmirage.inspector.preview").toUpperCase(Locale.ROOT), x + 12, y + 11, Theme.CAPTION, Theme.TEXT_TERTIARY);
			if (page != Page.MASCOTS && (page.slot != null || selectedModel != null && CatalogService.isLoaded(selectedModel))) {
				String rotate = Lang.tr("modelmirage.inspector.rotate");
				MirageUi.MEDIUM.draw(graphics, rotate, x + width - 12 - MirageUi.MEDIUM.width(rotate, 6.5f), y + 11.5f, 6.5f, Theme.TEXT_TERTIARY);
			}
			previewTop = y + 26;
			previewHeight = page == Page.MASCOTS ? 150 : 196;
			float px = x + 8, pw = width - 16;
			MirageUi.shape(graphics, px, previewTop, pw, previewHeight, 9, 0, 0x0FFFFFFF, 0x05FFFFFF);
			drawPreview(graphics, px, previewTop, pw, previewHeight);
			float textY = previewTop + previewHeight + 10;
			String title = selectedModel != null ? selectedModel.name() : page == Page.ITEMS ? selectedItem == null ? Lang.tr("modelmirage.inspector.pick_item") : itemName(selectedItem) : Lang.tr("modelmirage.inspector.default_view");
			MirageUi.BOLD.draw(graphics, MirageUi.BOLD.fit(title, 10.5f, width - 24), x + 12, textY, 10.5f, Theme.TEXT);
			String subtitle = subtitle();
			MirageUi.MEDIUM.draw(graphics, MirageUi.MEDIUM.fit(subtitle, Theme.CAPTION, width - 24), x + 12, textY + 15, Theme.CAPTION, failed ? Theme.DANGER : Theme.TEXT_SECONDARY);
			float buttonsY = y + height - 38;
			if (page == Page.MASCOTS) {
				mascot.layout(x + 6, textY + 28, width - 12);
				mascot.render(surface);
			}
			float half = (width - 30) / 2;
			apply.text = failed ? Lang.tr("modelmirage.inspector.retry") : downloading ? Lang.tr("modelmirage.state.loading") : selectedModel != null && isApplied(selectedModel) ? Lang.tr("modelmirage.inspector.applied") : Lang.tr("modelmirage.inspector.apply");
			apply.height = reset.height = 26;
			apply.layout(x + 12, buttonsY, half);
			reset.layout(x + 18 + half, buttonsY, half);
			apply.render(surface);
			reset.render(surface);
		}

		private String subtitle() {
			if (selectedModel == null) {
				if (page == Page.ITEMS && selectedItem == null) return Lang.tr("modelmirage.inspector.items_grid");
				Identifier applied = appliedId();
				return applied == null ? page == Page.ITEMS ? itemId(selectedItem).toString() : Lang.tr("modelmirage.inspector.no_model") : Lang.tr("modelmirage.inspector.current", modelName(applied));
			}
			if (failed) return Lang.tr("modelmirage.inspector.load_failed");
			if (downloading) return Lang.tr("modelmirage.inspector.loading");
			if (isApplied(selectedModel)) return Lang.tr("modelmirage.inspector.applied_group", ModelTile.groupName(selectedModel));
			return ModelTile.groupName(selectedModel) + ", " + selectedModel.format() + (selectedModel.builtin() ? "" : ", " + ModelTile.size(selectedModel.bytes()));
		}

		private void drawPreview(GuiGraphicsExtractor graphics, float px, float py, float pw, float ph) {
			graphics.enableScissor(Math.round(px), Math.round(py), Math.round(px + pw), Math.round(py + ph));
			float cx = px + pw / 2, cy = py + ph / 2;
			if (page == Page.MASCOTS) {
				Identifier preview = selectedModel != null && CatalogService.isLoaded(selectedModel) ? selectedModel.modelId() : CosmeticSelections.selected(CosmeticSelections.Slot.MASCOT);
				float ratio = Math.min((pw - 40) / MascotAnimations.width(preview), (ph - 24) / MascotAnimations.height(preview));
				int w = Math.round(MascotAnimations.width(preview) * ratio), h = Math.round(MascotAnimations.height(preview) * ratio);
				MascotAnimations.draw(graphics, preview, Math.round(cx - w / 2f), Math.round(cy - h / 2f), w, h, MirageUi.opacity(0xFFFFFFFF, MirageUi.alpha));
			} else if (selectedModel != null && CatalogService.isLoaded(selectedModel) && !(page.slot != null && isApplied(selectedModel))) {
				RuntimeModels.preview(graphics, selectedModel.modelId(), cx, cy, 120, previewYaw, previewPitch);
			} else if (selectedModel != null && !CatalogService.isLoaded(selectedModel)) {
				// The chosen model is not ready: say so instead of showing the current look as if it were the choice.
				boolean unsupported = !CatalogService.supports(selectedModel);
				String state = Lang.tr(unsupported ? "modelmirage.tile.needs_loader" : failed ? "modelmirage.tile.load_failed" : "modelmirage.state.loading");
				int color = unsupported ? Theme.WARNING : failed ? Theme.DANGER : Theme.TEXT_TERTIARY;
				MirageUi.shape(graphics, cx - 20, cy - 34, 40, 40, 12, 0, 0x14FFFFFF, 0x0AFFFFFF);
				MirageUi.ICONS.draw(graphics, page.slot != null ? "L" : "E", cx - 9, cy - 23, 18, Theme.TEXT_TERTIARY);
				MirageUi.MEDIUM.draw(graphics, state, cx - MirageUi.MEDIUM.width(state, Theme.BODY) / 2, cy + 16, Theme.BODY, color);
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
				MirageUi.ICONS.draw(graphics, "E", cx - 12, cy - 18, 24, Theme.TEXT_TERTIARY);
				String hint = Lang.tr("modelmirage.inspector.no_item");
				MirageUi.MEDIUM.draw(graphics, hint, cx - MirageUi.MEDIUM.width(hint, Theme.BODY) / 2, cy + 12, Theme.BODY, Theme.TEXT_TERTIARY);
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
