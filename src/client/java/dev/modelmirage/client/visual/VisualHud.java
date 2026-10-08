package dev.modelmirage.client.visual;

import dev.modelmirage.client.gui.AstolfoUi;
import dev.modelmirage.client.CosmeticSelections;
import dev.modelmirage.client.render.MascotAnimations;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Astolfo's HUD composition, sizing and easing, adapted to Minecraft's extraction renderer. */
public final class VisualHud {
	private static final List<Panel> PANELS = new ArrayList<>();
	private static final Map<String, Motion> MOTION = new HashMap<>();
	private static final Map<String, AnimatedText> TEXT = new HashMap<>();
	private static final Map<String, MobEffectInstance> EFFECTS = new LinkedHashMap<>();
	private static LivingEntity target;
	private static long targetTime;
	private static float targetHealth;
	private static float damageHealth;
	private static float absorption;
	private static double speed;
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
		if (client.player == null || target != null && target.level() != client.level) {
			target = null;
			EFFECTS.clear();
			MOTION.clear();
			TEXT.clear();
			speed = 0;
		}
		if (client.player != null) {
			double dx = client.player.getX() - client.player.xo;
			double dz = client.player.getZ() - client.player.zo;
			speed += (Math.hypot(dx, dz) * 20 - speed) * 0.35;
			if (speed < 0.01) speed = 0;
		}
		if (client.crosshairPickEntity instanceof LivingEntity entity) hit(entity);
	}

	public static void hit(LivingEntity entity) {
		if (target != entity) {
			targetHealth = damageHealth = entity.getHealth();
			absorption = entity.getAbsorptionAmount();
		}
		target = entity;
		targetTime = System.currentTimeMillis();
	}

	public static List<Panel> panels() { return List.copyOf(PANELS); }
	private static boolean show(String setting, boolean editor) {
		return editor || Visuals.enabled("Interface") && Visuals.module("Interface").bool(setting);
	}

	private static void extract(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.gui.hud.isHidden() || client.gui.screen() instanceof dev.modelmirage.client.ModelMirageScreen
				|| client.gui.screen() instanceof AppearanceScreen || client.gui.screen() instanceof HudEditorScreen) return;
		render(graphics, false);
	}

	public static void render(GuiGraphicsExtractor graphics, boolean editor) {
		Minecraft client = Minecraft.getInstance();
		PANELS.clear();
		if (client.player == null) return;
		long now = System.nanoTime();
		seconds = Math.clamp((now - frame) / 1e9f, 0.0005f, 0.1f);
		frame = now;
		// Upstream fixes the physical size independently of Minecraft's GUI scale.
		hudScale = (float) (2f / client.getWindow().getGuiScale()) * Visuals.module("Interface").number("hudScale");
		graphics.pose().pushMatrix();
		graphics.pose().scale(hudScale, hudScale);
		int width = Math.round(graphics.guiWidth() / hudScale);
		int height = Math.round(graphics.guiHeight() / hudScale);
		watermark(graphics, editor, width);
		info(graphics, editor, height);
		target(graphics, editor, width, height);
		effects(graphics, editor, height);
		inventory(graphics, editor, width, height);
		armor(graphics, editor, width, height);
		active(graphics, editor, width);
		binds(graphics, editor, width, height);
		if (Visuals.enabled("Crosshair") && !editor) crosshair(graphics);
		boykisser(graphics, editor);
		graphics.pose().popMatrix();
	}

	private static Motion motion(String id) { return MOTION.computeIfAbsent(id, unused -> new Motion()); }

	private static Panel locate(GuiGraphicsExtractor graphics, String id, String label, float defaultX, float defaultY, int width, int height) {
		int logicalWidth = Math.round(graphics.guiWidth() / hudScale);
		int logicalHeight = Math.round(graphics.guiHeight() / hudScale);
		HudLayout.Position position = HudLayout.position(id, defaultX, defaultY, logicalWidth, logicalHeight);
		int x = Math.clamp(Math.round(position.x() * logicalWidth), 0, Math.max(0, logicalWidth - Math.round(width * position.scale())));
		int y = Math.clamp(Math.round(position.y() * logicalHeight), 0, Math.max(0, logicalHeight - Math.round(height * position.scale())));
		Panel panel = new Panel(id, label, x, y, width, height, hudScale, position.scale());
		PANELS.add(panel);
		return panel;
	}

	private static Panel begin(GuiGraphicsExtractor graphics, String id, String label, float x, float y, float width, float height, boolean visible, boolean background, boolean editor) {
		Motion state = motion(id);
		state.alpha = editor ? 1 : AstolfoUi.animate(state.alpha, visible ? 1 : 0, 14, seconds);
		state.width = state.width == 0 || editor ? width : AstolfoUi.animate(state.width, width, 15, seconds);
		state.height = state.height == 0 || editor ? height : AstolfoUi.animate(state.height, height, 15, seconds);
		if (state.alpha < 0.005f) return null;
		Panel panel = locate(graphics, id, label, x, y, Math.round(state.width), Math.round(state.height));
		graphics.pose().pushMatrix();
		graphics.pose().translate(panel.x(), panel.y());
		graphics.pose().scale(panel.zoom(), panel.zoom());
		graphics.pose().translate(-panel.x(), -panel.y());
		float pop = editor ? 1 : 0.86f + 0.14f * (1 - (float) Math.pow(1 - state.alpha, 3));
		graphics.pose().translate(panel.x() + panel.width() / 2f, panel.y() + panel.height() / 2f);
		graphics.pose().scale(pop, pop);
		graphics.pose().translate(-panel.x() - panel.width() / 2f, -panel.y() - panel.height() / 2f);
		AstolfoUi.alpha = state.alpha;
		if (background) background(graphics, panel.x(), panel.y(), panel.width(), panel.height(), 6);
		return panel;
	}

	private static void background(GuiGraphicsExtractor graphics, float x, float y, float width, float height, float radius) {
		AstolfoUi.shadow(graphics, x, y, width, height, radius);
		AstolfoUi.glass(graphics, x, y, width, height, radius, 6, 0x5A0C0C10);
	}

	private static void end(GuiGraphicsExtractor graphics) { AstolfoUi.alpha = 1; graphics.pose().popMatrix(); }

	private static void icon(GuiGraphicsExtractor graphics, AstolfoUi.Face face, String glyph, float x, float y, float size) {
		AstolfoUi.glow(graphics, x + size / 2, y + size / 2, size / 2, AstolfoUi.ACCENT, 0.12f);
		face.draw(graphics, glyph, x, y, size, AstolfoUi.ACCENT);
	}

	private static void animated(GuiGraphicsExtractor graphics, String id, String value, float x, float y, float size, int color) {
		TEXT.computeIfAbsent(id, unused -> new AnimatedText()).draw(graphics, value, x, y, size, color);
	}

	private static void watermark(GuiGraphicsExtractor graphics, boolean editor, int width) {
		Minecraft client = Minecraft.getInstance();
		int ping = client.getConnection() == null || client.getConnection().getPlayerInfo(client.player.getUUID()) == null ? 0 : client.getConnection().getPlayerInfo(client.player.getUUID()).getLatency();
		String[] values = {"Astolfo Visuals", ping + " MS", client.getFps() + " FPS", AstolfoUi.SEMIBOLD.fit(client.player.getName().getString(), 8, 80)};
		String[] glyphs = {"A", "D", "C", "B"};
		float total = 34;
		for (String value : values) total += AstolfoUi.SEMIBOLD.width(value, 8) + 25;
		Panel panel = begin(graphics, "logo", "Watermark", (width - total) / 2, 10, total, 23, show("logo", editor), true, editor);
		if (panel == null) return;
		float x = panel.x() + 7;
		for (int i = 0; i < values.length; i++) {
			icon(graphics, i == 0 ? AstolfoUi.LOGO : AstolfoUi.WATERMARK, glyphs[i], x, panel.y() + (i == 0 ? 4 : 7), i == 0 ? 14 : 9);
			x += i == 0 ? 18 : 13;
			animated(graphics, "watermark" + i, values[i], x, panel.y() + 8, 8, AstolfoUi.TEXT);
			x += AstolfoUi.SEMIBOLD.width(values[i], 8) + 7;
			if (i < values.length - 1) { AstolfoUi.shape(graphics, x, panel.y() + 10, 1.8f, 1.8f, 0.9f, 0, 0xFF828287, 0xFF828287); x += 6; }
		}
		face(graphics, client.player, Math.round(panel.x() + panel.width() - 18), panel.y() + 5, 13);
		end(graphics);
	}

	private static void info(GuiGraphicsExtractor graphics, boolean editor, int height) {
		var pos = Minecraft.getInstance().player.position();
		String coordinates = (int) pos.x + "X " + (int) pos.y + "Y " + (int) pos.z + "Z";
		String bps = String.format(Locale.ROOT, "%.2f BPS", speed);
		float w = 40 + AstolfoUi.SEMIBOLD.width(coordinates, 7) + AstolfoUi.SEMIBOLD.width(bps, 7);
		Panel panel = begin(graphics, "info", "Координаты и скорость", 10, height - 25, w, 18, show("infoHud", editor), true, editor);
		if (panel == null) return;
		icon(graphics, AstolfoUi.INTERFACE, "C", panel.x() + 6, panel.y() + 5, 7);
		animated(graphics, "coords", coordinates, panel.x() + 17, panel.y() + 6, 7, AstolfoUi.TEXT);
		float x = panel.x() + 23 + AstolfoUi.SEMIBOLD.width(coordinates, 7);
		icon(graphics, AstolfoUi.INTERFACE, "B", x, panel.y() + 5, 7);
		animated(graphics, "bps", bps, x + 11, panel.y() + 6, 7, AstolfoUi.TEXT);
		end(graphics);
	}

	private static void target(GuiGraphicsExtractor graphics, boolean editor, int width, int height) {
		Minecraft client = Minecraft.getInstance();
		LivingEntity entity = editor ? client.player : target;
		boolean visible = show("targetHud", editor) && entity != null && entity.isAlive() && (editor || System.currentTimeMillis() - targetTime <= 250);
		Panel panel = begin(graphics, "target", "Цель", width * 0.545f, height * 0.585f, 122, 33, visible, true, editor);
		if (panel == null) return;
		if (entity != null) {
			targetHealth = AstolfoUi.animate(targetHealth, entity.getHealth(), 12, seconds);
			damageHealth = AstolfoUi.animate(damageHealth, entity.getHealth(), 6, seconds);
			absorption = AstolfoUi.animate(absorption, entity.getAbsorptionAmount(), 12, seconds);
			face(graphics, entity, panel.x() + 5, panel.y() + 5, 22);
			AstolfoUi.BOLD.draw(graphics, AstolfoUi.BOLD.fit(entity.getName().getString(), 8.5f, 84), panel.x() + 33, panel.y() + 5, 8.5f, AstolfoUi.TEXT);
			animated(graphics, "hp", String.format(Locale.ROOT, "%.1f HP", targetHealth), panel.x() + 33, panel.y() + 14, 7, AstolfoUi.MUTED);
			float maximum = Math.max(1, entity.getMaxHealth() + absorption);
			float barX = panel.x() + 33;
			float barY = panel.y() + 24;
			AstolfoUi.shape(graphics, barX, barY, 83, 3.2f, 1.6f, 0, AstolfoUi.LINE, AstolfoUi.LINE);
			AstolfoUi.shape(graphics, barX, barY, 83 * Math.clamp(damageHealth / maximum, 0, 1), 3.2f, 1.6f, 0, 0xFFEB3C3C, 0xFFEB3C3C);
			float hpWidth = 83 * Math.clamp(targetHealth / maximum, 0, 1);
			AstolfoUi.glow(graphics, barX + hpWidth / 2, barY + 1, 2, AstolfoUi.ACCENT, 0.18f);
			AstolfoUi.shape(graphics, barX, barY, hpWidth, 3.2f, 1.6f, 0, AstolfoUi.ACCENT, AstolfoUi.mix(AstolfoUi.ACCENT, 0xFFB432FF, 0.3f));
			if (absorption > 0.1f) AstolfoUi.shape(graphics, barX + hpWidth, barY, 83 * absorption / maximum, 3.2f, 1.6f, 0, 0xFFFFDC28, 0xFFFFA00A);
		}
		end(graphics);
	}

	private static void face(GuiGraphicsExtractor graphics, LivingEntity entity, int x, int y, int size) {
		AstolfoUi.glow(graphics, x + size / 2f, y + size / 2f, size / 2f, AstolfoUi.ACCENT, 0.12f);
		if (entity instanceof AbstractClientPlayer player) {
			Identifier skin = player.getSkin().body().texturePath();
			AstolfoUi.head(graphics, skin, x, y, size, true, entity.hurtTime > 0 ? 0xFFFF8080 : AstolfoUi.TEXT);
		} else {
			Identifier image = Identifier.fromNamespaceAndPath("modelmirage", "astolfo/mob_icon.png");
			AstolfoUi.head(graphics, image, x, y, size, false, AstolfoUi.TEXT);
		}
	}

	private static void effects(GuiGraphicsExtractor graphics, boolean editor, int height) {
		List<MobEffectInstance> current = editor ? List.of(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 16500), new MobEffectInstance(MobEffects.STRENGTH, 740, 1), new MobEffectInstance(MobEffects.SPEED, 1640, 1), new MobEffectInstance(MobEffects.ABSORPTION, 2260, 3))
				: new ArrayList<>(Minecraft.getInstance().player.getActiveEffects());
		boolean enabled = show("effectHud", editor);
		List<String> active = new ArrayList<>();
		for (var effect : current) {
			String id = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()).toString();
			active.add(id); EFFECTS.put(id, effect);
		}
		float maxWidth = 80;
		float totalHeight = 0;
		for (var entry : EFFECTS.entrySet()) {
			Motion state = motion("effect:" + entry.getKey());
			state.alpha = editor ? 1 : AstolfoUi.animate(state.alpha, enabled && active.contains(entry.getKey()) ? 1 : 0, 14, seconds);
			state.height = editor ? 30 : AstolfoUi.animate(state.height, state.alpha < 0.35f ? 0 : 30, 14, seconds);
			String name = Component.translatable(entry.getValue().getEffect().value().getDescriptionId()).getString();
			float w = 38 + AstolfoUi.BOLD.width(name + (entry.getValue().getAmplifier() > 0 ? " " + (entry.getValue().getAmplifier() + 1) : ""), 8);
			state.width = state.width == 0 ? w : AstolfoUi.animate(state.width, w, 14, seconds);
			maxWidth = Math.max(maxWidth, state.width);
			totalHeight += state.height;
		}
		if (totalHeight > 1) {
			Panel panel = locate(graphics, "effects", "Эффекты", 10, (height - totalHeight) / 2, Math.round(maxWidth), Math.max(26, Math.round(totalHeight - 4)));
			graphics.pose().pushMatrix();
			graphics.pose().translate(panel.x(), panel.y());
			graphics.pose().scale(panel.zoom(), panel.zoom());
			graphics.pose().translate(-panel.x(), -panel.y());
			float y = panel.y();
			for (var entry : EFFECTS.entrySet()) {
				Motion state = motion("effect:" + entry.getKey());
				if (state.alpha >= 0.005f) {
					AstolfoUi.alpha = state.alpha;
					float x = panel.x() + (1 - state.alpha) * -12;
					background(graphics, x, y, state.width, 26, 6);
					var effect = entry.getValue();
					Identifier key = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value());
					Identifier sprite = Identifier.fromNamespaceAndPath(key.getNamespace(), "mob_effect/" + key.getPath());
					AstolfoUi.glow(graphics, x + 15, y + 13, 7, AstolfoUi.ACCENT, 0.12f);
					graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, Math.round(x + 8), Math.round(y + 6), 14, 14, AstolfoUi.opacity(0xFFFFFFFF, state.alpha));
					String name = Component.translatable(effect.getEffect().value().getDescriptionId()).getString();
					AstolfoUi.BOLD.draw(graphics, name, x + 28, y + 5, 8, AstolfoUi.TEXT);
					if (effect.getAmplifier() > 0) AstolfoUi.BOLD.draw(graphics, String.valueOf(effect.getAmplifier() + 1), x + 30 + AstolfoUi.BOLD.width(name, 8), y + 5, 8, AstolfoUi.ACCENT);
					String time = effect.isInfiniteDuration() ? "∞" : effect.getDuration() / 1200 + ":" + String.format(Locale.ROOT, "%02d", effect.getDuration() / 20 % 60);
					animated(graphics, "duration:" + entry.getKey(), time, x + 28, y + 15, 7, AstolfoUi.MUTED);
				}
				y += state.height;
			}
			AstolfoUi.alpha = 1;
			graphics.pose().popMatrix();
		}
		EFFECTS.entrySet().removeIf(entry -> !active.contains(entry.getKey()) && motion("effect:" + entry.getKey()).alpha < 0.005f);
	}

	private static void inventory(GuiGraphicsExtractor graphics, boolean editor, int width, int height) {
		Panel panel = begin(graphics, "inventory", "Инвентарь", width / 2f - 81, height * 0.70f, 162, 54, show("inventoryHud", editor), false, editor);
		if (panel == null) return;
		for (int slot = 0; slot < 27; slot++) {
			ItemStack item = Minecraft.getInstance().player.getInventory().getItem(slot + 9);
			if (editor && slot < 9 && item.isEmpty()) {
				var examples = List.of(Items.DIAMOND_SWORD, Items.GOLDEN_APPLE, Items.ENDER_PEARL, Items.COBWEB, Items.OBSIDIAN, Items.TOTEM_OF_UNDYING, Items.DIAMOND_PICKAXE, Items.BOW, Items.ARROW);
				item = new ItemStack(examples.get(slot), slot == 0 || slot == 5 || slot == 6 || slot == 7 ? 1 : 64);
			}
			int x = panel.x() + slot % 9 * 18;
			int y = panel.y() + slot / 9 * 18;
			graphics.item(item, x, y);
			graphics.itemDecorations(Minecraft.getInstance().font, item, x, y);
		}
		end(graphics);
	}

	private static void armor(GuiGraphicsExtractor graphics, boolean editor, int width, int height) {
		Panel panel = begin(graphics, "armor", "Броня", width / 2f + 10, height - 57, 76, 18, show("armorHud", editor), false, editor);
		if (panel == null) return;
		EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		var examples = List.of(Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS);
		for (int index = 0; index < slots.length; index++) {
			ItemStack item = Minecraft.getInstance().player.getItemBySlot(slots[index]);
			if (editor && item.isEmpty()) item = new ItemStack(examples.get(index));
			int x = panel.x() + index * 20;
			graphics.item(item, x, panel.y());
			graphics.itemDecorations(Minecraft.getInstance().font, item, x, panel.y());
		}
		end(graphics);
	}

	private static void active(GuiGraphicsExtractor graphics, boolean editor, int width) {
		List<VisualModule> modules = Visuals.modules().stream().sorted(Comparator.comparingDouble((VisualModule module) -> AstolfoUi.SEMIBOLD.width(module.name, 7)).reversed()).toList();
		float w = 60;
		float h = 0;
		for (var module : modules) {
			Motion row = motion("module:" + module.id);
			row.alpha = editor ? (module.enabled ? 1 : 0) : AstolfoUi.animate(row.alpha, module.enabled ? 1 : 0, 14, seconds);
			row.height = AstolfoUi.animate(row.height, row.alpha < 0.1f ? 0 : 20, 14, seconds);
			if (row.alpha >= 0.005f) w = Math.max(w, AstolfoUi.SEMIBOLD.width(module.name, 7) + 25);
			h += row.height;
		}
		Panel panel = begin(graphics, "modules", "Активные модули", width - w - 15, 210, w, Math.max(16, h - 4), show("arrayList", editor) && h > 1, false, editor);
		if (panel == null) return;
		float alpha = AstolfoUi.alpha;
		float y = panel.y();
		for (var module : modules) {
			Motion row = motion("module:" + module.id);
			if (row.alpha >= 0.005f) {
				AstolfoUi.alpha = alpha * row.alpha;
				float rowWidth = AstolfoUi.SEMIBOLD.width(module.name, 7) + 25;
				float x = panel.x() + panel.width() - rowWidth + (1 - row.alpha) * 10;
				background(graphics, x, y, rowWidth, 16, 5);
				icon(graphics, AstolfoUi.LOGO, "A", x + 5, y + 3, 10);
				AstolfoUi.SEMIBOLD.draw(graphics, module.name, x + 19, y + 5, 7, AstolfoUi.TEXT);
			}
			y += row.height;
		}
		end(graphics);
	}

	private static void binds(GuiGraphicsExtractor graphics, boolean editor, int width, int height) {
		List<VisualModule> modules = Visuals.modules();
		float w = 90;
		float h = 0;
		for (var module : modules) {
			Motion row = motion("bind:" + module.id);
			boolean visible = module.bind >= 0 && module.enabled;
			row.alpha = editor ? (visible ? 1 : 0) : AstolfoUi.animate(row.alpha, visible ? 1 : 0, 14, seconds);
			row.height = editor ? (visible ? 15 : 0) : AstolfoUi.animate(row.height, visible ? 15 : 0, 14, seconds);
			if (row.alpha >= 0.005f) w = Math.max(w, AstolfoUi.MEDIUM.width(module.name, 7) + 40);
			h += row.height;
		}
		Panel panel = begin(graphics, "binds", "Keybinds", width * 0.30f, height / 3f, w, 25 + Math.max(editor ? 15 : 0, h), show("activeBinds", editor) && (editor || h > 0.1f), true, editor);
		if (panel == null) return;
		icon(graphics, AstolfoUi.INTERFACE, "K", panel.x() + 8, panel.y() + 7, 8);
		AstolfoUi.SEMIBOLD.draw(graphics, "Keybinds", panel.x() + 20, panel.y() + 7, 8, AstolfoUi.TEXT);
		AstolfoUi.shape(graphics, panel.x() + 7, panel.y() + 21, panel.width() - 14, 1, 0, 0, AstolfoUi.LINE, AstolfoUi.LINE);
		float alpha = AstolfoUi.alpha;
		float y = panel.y() + 27;
		if (editor && h < 1) AstolfoUi.MEDIUM.draw(graphics, "Назначь клавишу в GUI", panel.x() + 7, y, 7, AstolfoUi.MUTED);
		for (var module : modules) {
			Motion row = motion("bind:" + module.id);
			if (module.bind >= 0 && row.alpha >= 0.005f) {
				AstolfoUi.alpha = alpha * row.alpha;
				AstolfoUi.MEDIUM.draw(graphics, module.name, panel.x() + 8, y + (1 - row.alpha) * -3, 7, AstolfoUi.TEXT);
				String key = com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(module.bind).getDisplayName().getString().toUpperCase(Locale.ROOT);
				key = AstolfoUi.MEDIUM.fit(key, 7, 25);
				AstolfoUi.MEDIUM.draw(graphics, key, panel.x() + panel.width() - AstolfoUi.MEDIUM.width(key, 7) - 8, y, 7, AstolfoUi.ACCENT);
			}
			y += row.height;
		}
		end(graphics);
	}

	private static void crosshair(GuiGraphicsExtractor graphics) {
		var module = Visuals.module("Crosshair");
		float x = graphics.guiWidth() / hudScale / 2f;
		float y = graphics.guiHeight() / hudScale / 2f;
		float gap = module.number("gap");
		if (module.bool("dynamicGap")) gap += speed * 0.5f;
		float length = module.number("length");
		float thickness = module.number("thickness");
		int color = module.bool("useEntityColor") && Minecraft.getInstance().crosshairPickEntity != null ? 0xFFFF7085 : AstolfoUi.ACCENT;
		AstolfoUi.shape(graphics, x - gap - length, y - thickness / 2, length, thickness, 0, 0, color, color);
		AstolfoUi.shape(graphics, x + gap, y - thickness / 2, length, thickness, 0, 0, color, color);
		AstolfoUi.shape(graphics, x - thickness / 2, y - gap - length, thickness, length, 0, 0, color, color);
		AstolfoUi.shape(graphics, x - thickness / 2, y + gap, thickness, length, 0, 0, color, color);
	}

	private static void boykisser(GuiGraphicsExtractor graphics, boolean editor) {
		Identifier selected = CosmeticSelections.selected(CosmeticSelections.Slot.MASCOT);
		Panel panel = begin(graphics, "boykisser", "Персонаж", 20, 50, MascotAnimations.width(selected), MascotAnimations.height(selected), editor || Visuals.enabled("BoyKisser"), false, editor);
		if (panel == null) return;
		MascotAnimations.draw(graphics, selected, panel.x(), panel.y(), panel.width(), panel.height(), AstolfoUi.opacity(0xFFFFFFFF, AstolfoUi.alpha));
		end(graphics);
	}

	public record Panel(String id, String label, int x, int y, int width, int height, float scale, float zoom) {
		public float screenX() { return x * scale; }
		public float screenY() { return y * scale; }
		public float screenWidth() { return width * scale * zoom; }
		public float screenHeight() { return height * scale * zoom; }
		public boolean contains(double x, double y) { return x >= screenX() && x < screenX() + screenWidth() && y >= screenY() && y < screenY() + screenHeight(); }
	}

	private static final class Motion { float alpha; float width; float height; }

	private static final class AnimatedText {
		private String value = "";
		private String previous = "";
		private long changed;
		void draw(GuiGraphicsExtractor graphics, String next, float x, float y, float size, int color) {
			if (!value.equals(next)) { previous = value; value = next; changed = System.currentTimeMillis(); }
			float progress = VisualPreferences.animations ? Math.clamp((System.currentTimeMillis() - changed) / 200f, 0, 1) : 1;
			for (int i = 0; i < value.length(); i++) {
				String glyph = value.substring(i, i + 1);
				if (i < previous.length() && previous.charAt(i) != value.charAt(i) && progress < 1) {
					AstolfoUi.SEMIBOLD.draw(graphics, previous.substring(i, i + 1), x, y - progress * size * 0.5f, size, AstolfoUi.opacity(color, 1 - progress));
					AstolfoUi.SEMIBOLD.draw(graphics, glyph, x, y + (1 - progress) * size * 0.5f, size, AstolfoUi.opacity(color, progress));
				} else AstolfoUi.SEMIBOLD.draw(graphics, glyph, x, y, size, color);
				x += AstolfoUi.SEMIBOLD.width(glyph, size);
			}
		}
	}
}
