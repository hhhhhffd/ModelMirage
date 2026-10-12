package dev.modelmirage.client.gui;

import dev.modelmirage.client.gui.kit.Control;
import dev.modelmirage.client.gui.kit.Surface;
import dev.modelmirage.client.gui.kit.Theme;
import java.util.ArrayList;
import java.util.List;

/** Responsive grid of tiles grouped under small-caps captions; empty sections are skipped. */
public final class TileGrid extends Control {
	private static final float MIN_TILE = 84, GAP = 8, CAPTION = 20;
	public final List<Section> sections = new ArrayList<>();
	/** Visible band; tiles outside it are not drawn (the item list has well over a thousand entries). */
	public float viewTop = -1e6f, viewBottom = 1e6f;

	public record Section(String caption, List<ModelTile> tiles) { }

	private List<ModelTile> all;

	/** Flattened tiles; sections are filled once right after construction, so the list is cached. */
	public List<ModelTile> tiles() {
		if (all == null) {
			all = new ArrayList<>();
			for (Section section : sections) all.addAll(section.tiles());
		}
		return all;
	}

	public boolean isEmpty() { return tiles().isEmpty(); }

	@Override
	public float layout(float x, float y, float width) {
		this.x = x;
		this.y = y;
		this.width = width;
		int columns = Math.max(1, (int) ((width + GAP) / (MIN_TILE + GAP)));
		float tile = (width - GAP * (columns - 1)) / columns;
		float cursor = y;
		for (Section section : sections) {
			if (section.tiles().isEmpty()) continue;
			if (!section.caption().isEmpty()) cursor += CAPTION;
			for (int i = 0; i < section.tiles().size(); i++) {
				section.tiles().get(i).layout(x + i % columns * (tile + GAP), cursor + i / columns * (ModelTile.HEIGHT + GAP), tile);
			}
			int rows = (section.tiles().size() + columns - 1) / columns;
			cursor += rows * (ModelTile.HEIGHT + GAP) + 4;
		}
		return height = Math.max(0, cursor - y - GAP);
	}

	@Override
	public void render(Surface surface) {
		for (Section section : sections) {
			if (section.tiles().isEmpty()) continue;
			if (!section.caption().isEmpty()) {
				float top = section.tiles().getFirst().y - CAPTION;
				if (top < viewBottom && top + CAPTION > viewTop) MirageUi.SEMIBOLD.draw(surface.graphics, section.caption().toUpperCase(java.util.Locale.ROOT) + "  " + section.tiles().size(), x + 2, top + 6, Theme.CAPTION, Theme.TEXT_TERTIARY);
			}
			for (ModelTile tile : section.tiles()) if (tile.y + tile.height > viewTop && tile.y < viewBottom) tile.render(surface);
		}
	}

	@Override
	public boolean mouseClicked(Surface surface, double mouseX, double mouseY, int button) {
		for (ModelTile tile : tiles()) if (tile.y + tile.height > viewTop && tile.y < viewBottom && tile.mouseClicked(surface, mouseX, mouseY, button)) return true;
		return false;
	}
}
