package dev.modelmirage.client;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class ModelMirageScreen extends Screen {
	private static final int ROWS = 4;
	private final List<Item> allItems = BuiltInRegistries.ITEM.stream()
			.filter(item -> item != Items.AIR)
			.sorted((a, b) -> BuiltInRegistries.ITEM.getKey(a).compareTo(BuiltInRegistries.ITEM.getKey(b)))
			.toList();
	private final Item[] shownItems = new Item[ROWS];
	private final Button[] itemButtons = new Button[ROWS];
	private List<Item> filteredItems = allItems;
	private EditBox search;
	private Button previous;
	private Button next;
	private Button modelButton;
	private Button applyButton;
	private Button resetButton;
	private Item selectedItem;
	private boolean selectedModel;
	private int page;
	private int x;
	private int y;
	private int w;
	private int h;
	private int itemW;
	private int modelW;
	private String status = "";

	public ModelMirageScreen() {
		super(Component.literal("ModelMirage"));
	}

	@Override
	protected void init() {
		w = Math.min(width - 16, 550);
		h = Math.min(height - 16, 248);
		x = (width - w) / 2;
		y = (height - h) / 2;
		itemW = w * 38 / 100;
		modelW = w * 27 / 100;

		search = new EditBox(font, x + 12, y + 58, itemW - 22, 18, Component.literal("Поиск предмета"));
		search.setHint(Component.literal("Поиск предмета"));
		search.setResponder(value -> {
			page = 0;
			filterItems();
		});
		addRenderableWidget(search);

		for (int row = 0; row < ROWS; row++) {
			int index = row;
			itemButtons[row] = addRenderableWidget(Button.builder(Component.empty(), button -> selectItem(shownItems[index]))
					.bounds(x + 12, y + 80 + row * 22, itemW - 22, 20).build());
		}
		previous = addRenderableWidget(Button.builder(Component.literal("‹"), button -> {
			page--;
			refreshItems();
		}).bounds(x + 12, y + 170, 24, 18).build());
		next = addRenderableWidget(Button.builder(Component.literal("›"), button -> {
			page++;
			refreshItems();
		}).bounds(x + itemW - 34, y + 170, 24, 18).build());
		modelButton = addRenderableWidget(Button.builder(Component.literal("Blood Axe Mace"), button -> {
			selectedModel = true;
			status = "";
			refreshSelection();
		}).bounds(x + itemW + 12, y + 80, modelW - 22, 24).build());
		applyButton = addRenderableWidget(Button.builder(Component.literal("Применить"), button -> apply(true))
				.bounds(x + 12, y + h - 32, 88, 20).build());
		resetButton = addRenderableWidget(Button.builder(Component.literal("Сбросить"), button -> apply(false))
				.bounds(x + 106, y + h - 32, 88, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Закрыть"), button -> onClose())
				.bounds(x + w - 84, y + h - 32, 72, 20).build());
		filterItems();
		refreshSelection();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, 0xB9000000);
		graphics.fill(x, y, x + w, y + h, 0xFF16191F);
		graphics.outline(x, y, w, h, 0xFF555E68);
		graphics.fill(x, y, x + 4, y + h, 0xFFB84A45);
		graphics.text(font, "MODELMIRAGE", x + 12, y + 11, 0xFFF3F0EA, false);
		graphics.text(font, "Предмет → модель → применить", x + 12, y + 26, 0xFF9DA5AC, false);
		graphics.fill(x + 7, y + 48, x + itemW - 2, y + h - 42, 0xFF222830);
		graphics.fill(x + itemW + 4, y + 48, x + itemW + modelW - 2, y + h - 42, 0xFF222830);
		graphics.fill(x + itemW + modelW + 4, y + 48, x + w - 7, y + h - 42, 0xFF101318);
		graphics.text(font, "ПРЕДМЕТ", x + 12, y + 53, 0xFF9DA5AC, false);
		graphics.text(font, "МОДЕЛЬ", x + itemW + 12, y + 53, 0xFF9DA5AC, false);
		graphics.text(font, "ПРОСМОТР", x + itemW + modelW + 12, y + 53, 0xFF9DA5AC, false);

		if (selectedItem == null) {
			graphics.text(font, "Выбери предмет", x + itemW + 12, y + 121, 0xFF9DA5AC, false);
		} else {
			if (selectedModel) graphics.outline(x + itemW + 9, y + 78, modelW - 16, 28, 0xFFB84A45);
			graphics.text(font, "Для любого предмета", x + itemW + 12, y + 113, 0xFF9DA5AC, false);
			ItemStack preview = new ItemStack(selectedItem);
			if (selectedModel) preview.set(DataComponents.ITEM_MODEL, ModelMirageClient.MODEL_ID);
			int previewX = x + itemW + modelW + (w - itemW - modelW) / 2;
			graphics.pose().pushMatrix();
			graphics.pose().translate(previewX, y + 124);
			graphics.pose().scale(4.0f, 4.0f);
			graphics.item(preview, -8, -8);
			graphics.pose().popMatrix();
			Identifier itemId = BuiltInRegistries.ITEM.getKey(selectedItem);
			graphics.text(font, fit(itemId.toString(), w - itemW - modelW - 22),
					x + itemW + modelW + 11, y + h - 56, 0xFF9DA5AC, false);
		}

		if (!status.isEmpty()) graphics.text(font, fit(status, w - 290), x + 202, y + h - 26, 0xFF9DA5AC, false);
		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	private void filterItems() {
		String query = search.getValue().toLowerCase(Locale.ROOT).trim();
		filteredItems = allItems.stream().filter(item ->
				BuiltInRegistries.ITEM.getKey(item).toString().contains(query)
						|| Component.translatable(item.getDescriptionId()).getString().toLowerCase(Locale.ROOT).contains(query))
				.toList();
		refreshItems();
	}

	private void refreshItems() {
		for (int row = 0; row < ROWS; row++) {
			int index = page * ROWS + row;
			boolean visible = index < filteredItems.size();
			itemButtons[row].visible = visible;
			shownItems[row] = visible ? filteredItems.get(index) : null;
			if (visible) {
				String name = Component.translatable(shownItems[row].getDescriptionId()).getString();
				itemButtons[row].setMessage(Component.literal(fit(name, itemW - 42)));
			}
		}
		previous.active = page > 0;
		next.active = (page + 1) * ROWS < filteredItems.size();
	}

	private void selectItem(Item item) {
		if (item == null) return;
		selectedItem = item;
		selectedModel = ItemModelOverrides.isEnabled(BuiltInRegistries.ITEM.getKey(item));
		status = "";
		refreshSelection();
	}

	private void refreshSelection() {
		modelButton.active = selectedItem != null;
		applyButton.active = selectedItem != null && selectedModel;
		resetButton.active = selectedItem != null && ItemModelOverrides.isEnabled(BuiltInRegistries.ITEM.getKey(selectedItem));
	}

	private void apply(boolean enabled) {
		if (selectedItem == null || enabled && !selectedModel) return;
		try {
			ItemModelOverrides.setEnabled(BuiltInRegistries.ITEM.getKey(selectedItem), enabled);
			selectedModel = enabled;
			status = enabled ? "Модель применена" : "Обычная модель восстановлена";
			refreshSelection();
		} catch (IOException error) {
			status = "Ошибка сохранения: " + error.getMessage();
		}
	}

	private String fit(String text, int maxWidth) {
		if (maxWidth <= 0) return "";
		if (font.width(text) <= maxWidth) return text;
		while (text.length() > 1 && font.width(text + "…") > maxWidth) text = text.substring(0, text.length() - 1);
		return text + "…";
	}
}
