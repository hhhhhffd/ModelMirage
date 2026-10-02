package dev.modelmirage.client.visual;

import dev.modelmirage.client.gui.AstolfoUi;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Astolfo HUD panels rendered through Fabric's extraction API with the common NL palette. */
public final class VisualHud {
	private static final List<Panel> PANELS = new ArrayList<>();
	private static LivingEntity target;
	private static long targetTime;
	private static float targetHealth;
	private static long frame = System.nanoTime();
	private static float seconds;
	private static float hudScale = 1;
	private VisualHud() { }

	public static void register() {
		HudLayout.load();
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("modelmirage", "visual_hud"), VisualHud::extract);
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, original -> (graphics, delta) -> {
			if (!Visuals.enabled("Crosshair")) original.extractRenderState(graphics, delta);
		});
		HudElementRegistry.replaceElement(VanillaHudElements.BOSS_BAR, original -> (graphics, delta) -> {
			if (!Visuals.enabled("NoRender") || !Visuals.module("NoRender").bool("bossbar")) original.extractRenderState(graphics, delta);
		});
		HudElementRegistry.replaceElement(VanillaHudElements.SCOREBOARD, original -> (graphics, delta) -> {
			if (!Visuals.enabled("NoRender") || !Visuals.module("NoRender").bool("scoreboard")) original.extractRenderState(graphics, delta);
		});
		HudElementRegistry.replaceElement(VanillaHudElements.MOB_EFFECTS, original -> (graphics, delta) -> {
			if (!show("effectHud", false)) original.extractRenderState(graphics, delta);
		});
	}

	public static void tick() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || target != null && target.level() != client.level) target = null;
		if (client.crosshairPickEntity instanceof LivingEntity entity) hit(entity);
	}

	public static void hit(LivingEntity entity) { target = entity; targetTime = System.currentTimeMillis(); }
	public static List<Panel> panels() { return List.copyOf(PANELS); }

	private static boolean show(String setting, boolean editor) {
		return editor || Visuals.enabled("Interface") && Visuals.module("Interface").bool(setting);
	}

	private static void extract(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.gui.hud.isHidden() || client.gui.screen() instanceof dev.modelmirage.client.ModelMirageScreen || client.gui.screen() instanceof AppearanceScreen || client.gui.screen() instanceof HudEditorScreen) return;
		render(graphics, false);
	}

	public static void render(GuiGraphicsExtractor graphics, boolean editor) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) return;
		long now = System.nanoTime();
		seconds = Math.min((now - frame) / 1e9f, 0.1f);
		frame = now;
		PANELS.clear();
		hudScale = Visuals.module("Interface").number("hudScale");
		graphics.pose().pushMatrix();
		graphics.pose().scale(hudScale, hudScale);
		int width = Math.round(graphics.guiWidth() / hudScale);
		int height = Math.round(graphics.guiHeight() / hudScale);
		if (show("logo", editor)) {
			Panel panel = panel(graphics, "logo", "Watermark", 8, 8, 194, 25);
			AstolfoUi.BOLD.draw(graphics, "ModelMirage", panel.x() + 8, panel.y() + 8, 12, AstolfoUi.TEXT);
			AstolfoUi.MEDIUM.draw(graphics, client.getFps() + " fps", panel.x() + 110, panel.y() + 9, 10, AstolfoUi.MUTED);
			int ping = client.getConnection() == null || client.getConnection().getPlayerInfo(client.player.getUUID()) == null ? 0 : client.getConnection().getPlayerInfo(client.player.getUUID()).getLatency();
			AstolfoUi.MEDIUM.draw(graphics, ping + " ms", panel.x() + 156, panel.y() + 9, 10, AstolfoUi.ACCENT);
		}
		if (show("infoHud", editor)) {
			Panel panel = panel(graphics, "info", "Info", 8, 39, 195, 23);
			var pos = client.player.position();
			String text = (int) pos.x + "  " + (int) pos.y + "  " + (int) pos.z + "   " + String.format(java.util.Locale.ROOT, "%.1f", client.player.getDeltaMovement().horizontalDistance() * 20) + " b/s";
			AstolfoUi.MEDIUM.draw(graphics, text, panel.x() + 8, panel.y() + 8, 10, AstolfoUi.TEXT);
		}
		if (show("targetHud", editor)) target(graphics, editor, width, height);
		if (show("effectHud", editor)) effects(graphics, width);
		if (show("inventoryHud", editor)) inventory(graphics, width, height);
		if (show("armorHud", editor)) armor(graphics, width, height);
		if (show("arrayList", editor)) active(graphics, width);
		if (show("activeBinds", editor)) binds(graphics);
		if (Visuals.enabled("Crosshair") && !editor) crosshair(graphics);
		if (Visuals.enabled("BoyKisser") || editor) boykisser(graphics, width, height);
		if (editor) for (Panel panel : PANELS) {
			AstolfoUi.border(graphics, panel.x(), panel.y(), panel.width(), panel.height(), 5, AstolfoUi.ACCENT);
		}
		graphics.pose().popMatrix();
	}

	private static Panel panel(GuiGraphicsExtractor graphics, String id, String label, float defaultX, float defaultY, int width, int height) {
		int logicalWidth = Math.round(graphics.guiWidth() / hudScale);
		int logicalHeight = Math.round(graphics.guiHeight() / hudScale);
		HudLayout.Position position = HudLayout.position(id, defaultX, defaultY, logicalWidth, logicalHeight);
		int x = Math.clamp(Math.round(position.x() * logicalWidth), 0, Math.max(0, logicalWidth - width));
		int y = Math.clamp(Math.round(position.y() * logicalHeight), 0, Math.max(0, logicalHeight - height));
		for (int spread = 3; spread > 0; spread--) AstolfoUi.rounded(graphics, x - spread, y - spread, width + spread * 2, height + spread * 2, 5 + spread, 0x10000000);
		AstolfoUi.shape(graphics, x, y, width, height, 5, 0, 0xEC111B28, 0xEC080F18);
		AstolfoUi.border(graphics, x, y, width, height, 5, 0xB51C3045);
		AstolfoUi.shape(graphics, x + 7, y, Math.max(8, width / 3f), 1, 0, 0, AstolfoUi.ACCENT, AstolfoUi.ACCENT);
		Panel panel = new Panel(id, label, x, y, width, height, hudScale);
		PANELS.add(panel);
		return panel;
	}

	private static void target(GuiGraphicsExtractor graphics, boolean editor, int width, int height) {
		Minecraft client = Minecraft.getInstance();
		LivingEntity entity = editor ? client.player : target;
		if (entity == null || !entity.isAlive() || !editor && System.currentTimeMillis() - targetTime > 5000) return;
		Panel panel = panel(graphics, "target", "Target", width / 2f + 35, height / 2f - 35, 155, 52);
		String name = AstolfoUi.SEMIBOLD.fit(entity.getName().getString(), 11, 137);
		AstolfoUi.SEMIBOLD.draw(graphics, name, panel.x() + 8, panel.y() + 8, 11, AstolfoUi.TEXT);
		float health = Math.clamp(entity.getHealth() / entity.getMaxHealth(), 0, 1);
		targetHealth = VisualPreferences.animations ? AstolfoUi.animate(targetHealth, health, 10, seconds) : health;
		AstolfoUi.MEDIUM.draw(graphics, Math.round(entity.getHealth()) + " HP", panel.x() + 8, panel.y() + 25, 9, AstolfoUi.MUTED);
		AstolfoUi.rounded(graphics, panel.x() + 8, panel.y() + 40, 139, 4, 2, AstolfoUi.LINE);
		AstolfoUi.rounded(graphics, panel.x() + 8, panel.y() + 40, Math.max(1, Math.round(139 * targetHealth)), 4, 2, AstolfoUi.ACCENT);
	}

	private static void effects(GuiGraphicsExtractor graphics, int width) {
		var effects = Minecraft.getInstance().player.getActiveEffects();
		Panel panel = panel(graphics, "effects", "Effects", width - 148, 8, 140, 27 + Math.max(1, effects.size()) * 17);
		AstolfoUi.SEMIBOLD.draw(graphics, "Эффекты", panel.x() + 8, panel.y() + 8, 11, AstolfoUi.TEXT);
		int y = panel.y() + 27;
		if (effects.isEmpty()) AstolfoUi.MEDIUM.draw(graphics, "Нет активных эффектов", panel.x() + 8, y, 9, AstolfoUi.MUTED);
		for (var effect : effects) {
			String name = net.minecraft.network.chat.Component.translatable(effect.getEffect().value().getDescriptionId()).getString();
			AstolfoUi.MEDIUM.draw(graphics, AstolfoUi.MEDIUM.fit(name, 9, 94), panel.x() + 8, y, 9, AstolfoUi.TEXT);
			int ticks = effect.getDuration();
			String time = effect.isInfiniteDuration() ? "inf" : ticks / 1200 + ":" + String.format(java.util.Locale.ROOT, "%02d", ticks / 20 % 60);
			AstolfoUi.MEDIUM.draw(graphics, time, panel.x() + 106, y, 9, AstolfoUi.ACCENT);
			y += 17;
		}
	}

	private static void inventory(GuiGraphicsExtractor graphics, int width, int height) {
		Panel panel = panel(graphics, "inventory", "Inventory", width - 180, height - 115, 172, 84);
		AstolfoUi.SEMIBOLD.draw(graphics, "Инвентарь", panel.x() + 8, panel.y() + 8, 11, AstolfoUi.TEXT);
		for (int slot = 0; slot < 27; slot++) {
			int x = panel.x() + 5 + slot % 9 * 18;
			int y = panel.y() + 25 + slot / 9 * 18;
			graphics.item(Minecraft.getInstance().player.getInventory().getItem(slot + 9), x, y);
		}
	}

	private static void armor(GuiGraphicsExtractor graphics, int width, int height) {
		Panel panel = panel(graphics, "armor", "Armor", width / 2f - 45, height - 67, 90, 28);
		EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		for (int index = 0; index < slots.length; index++) {
			ItemStack item = Minecraft.getInstance().player.getItemBySlot(slots[index]);
			int x = panel.x() + 7 + index * 20;
			graphics.item(item, x, panel.y() + 5);
			if (!item.isEmpty() && item.isDamageableItem()) {
				float durability = 1 - item.getDamageValue() / (float) item.getMaxDamage();
				graphics.fill(x, panel.y() + 23, x + Math.max(1, (int) (16 * durability)), panel.y() + 24, AstolfoUi.ACCENT);
			}
		}
	}

	private static void active(GuiGraphicsExtractor graphics, int width) {
		List<VisualModule> active = Visuals.modules().stream().filter(module -> module.enabled)
				.sorted((a, b) -> Float.compare(AstolfoUi.MEDIUM.width(b.name, 10), AstolfoUi.MEDIUM.width(a.name, 10))).toList();
		Panel panel = panel(graphics, "modules", "Modules", width - 130, 90, 122, 26 + Math.max(1, active.size()) * 16);
		AstolfoUi.SEMIBOLD.draw(graphics, "Активные", panel.x() + 8, panel.y() + 8, 11, AstolfoUi.TEXT);
		int y = panel.y() + 27;
		for (var module : active) { AstolfoUi.MEDIUM.draw(graphics, module.name, panel.x() + 8, y, 10, AstolfoUi.ACCENT); y += 16; }
	}

	private static void binds(GuiGraphicsExtractor graphics) {
		List<VisualModule> binds = Visuals.modules().stream().filter(module -> module.bind >= 0).toList();
		Panel panel = panel(graphics, "binds", "Binds", 8, 80, 148, 27 + Math.max(1, binds.size()) * 17);
		AstolfoUi.SEMIBOLD.draw(graphics, "Клавиши", panel.x() + 8, panel.y() + 8, 11, AstolfoUi.TEXT);
		int y = panel.y() + 27;
		if (binds.isEmpty()) AstolfoUi.MEDIUM.draw(graphics, "Клавиши не назначены", panel.x() + 8, y, 9, AstolfoUi.MUTED);
		for (var module : binds) {
			AstolfoUi.MEDIUM.draw(graphics, AstolfoUi.MEDIUM.fit(module.name, 10, 88), panel.x() + 8, y, 10, module.enabled ? AstolfoUi.TEXT : AstolfoUi.MUTED);
			String key = com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(module.bind).getDisplayName().getString();
			AstolfoUi.MEDIUM.draw(graphics, AstolfoUi.MEDIUM.fit(key, 9, 36), panel.x() + 104, y, 9, AstolfoUi.ACCENT);
			y += 17;
		}
	}

	private static void crosshair(GuiGraphicsExtractor graphics) {
		var module = Visuals.module("Crosshair");
		float x = graphics.guiWidth() / hudScale / 2f;
		float y = graphics.guiHeight() / hudScale / 2f;
		float gap = module.number("gap");
		if (module.bool("dynamicGap")) gap += (float) Minecraft.getInstance().player.getDeltaMovement().horizontalDistance() * 10;
		float length = module.number("length");
		float thickness = module.number("thickness");
		int color = module.bool("useEntityColor") && Minecraft.getInstance().crosshairPickEntity != null ? 0xFFFF7085 : AstolfoUi.ACCENT;
		AstolfoUi.shape(graphics, x - gap - length, y - thickness / 2, length, thickness, 0, 0, color, color);
		AstolfoUi.shape(graphics, x + gap, y - thickness / 2, length, thickness, 0, 0, color, color);
		AstolfoUi.shape(graphics, x - thickness / 2, y - gap - length, thickness, length, 0, 0, color, color);
		AstolfoUi.shape(graphics, x - thickness / 2, y + gap, thickness, length, 0, 0, color, color);
	}

	private static void boykisser(GuiGraphicsExtractor graphics, int width, int height) {
		Panel panel = panel(graphics, "boykisser", "BoyKisser", 8, height - 72, 58, 58);
		int frame = VisualPreferences.animations ? (int) (System.currentTimeMillis() / 100 % 53) : 0;
		Identifier image = Identifier.fromNamespaceAndPath("modelmirage", "astolfo/boykisser/boykisser_" + frame + ".png");
		graphics.blit(RenderPipelines.GUI_TEXTURED, image, panel.x() + 3, panel.y() + 3, 0, 0, 52, 52, 52, 52);
	}

	public record Panel(String id, String label, int x, int y, int width, int height, float scale) {
		public float screenX() { return x * scale; }
		public float screenY() { return y * scale; }
		public float screenWidth() { return width * scale; }
		public float screenHeight() { return height * scale; }
		public boolean contains(double x, double y) { return x >= screenX() && x < screenX() + screenWidth() && y >= screenY() && y < screenY() + screenHeight(); }
	}
}
