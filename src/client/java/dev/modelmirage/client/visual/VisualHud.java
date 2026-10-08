package dev.modelmirage.client.visual;

import dev.modelmirage.client.gui.AstolfoUi;
import dev.modelmirage.client.gui.kit.Paint;
import dev.modelmirage.client.gui.kit.Theme;
import dev.modelmirage.client.CosmeticSelections;
import dev.modelmirage.client.render.MascotAnimations;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
	private static final float CHIP_GAP = 15;
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
			if (!Visuals.enabled("Effects")) original.extractRenderState(graphics, delta);
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
	private static boolean on(String module, boolean editor) { return editor || Visuals.enabled(module); }

	private static void extract(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.gui.hud.isHidden() || client.gui.screen() instanceof dev.modelmirage.client.ModelMirageScreen
				|| client.gui.screen() instanceof HudEditorScreen) return;
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
		moduleList(graphics, editor, width);
		binds(graphics, editor, width, height);
		if (Visuals.enabled("Crosshair") && !editor) crosshair(graphics);
		boykisser(graphics, editor);
		graphics.pose().popMatrix();
	}

	private static Motion motion(String id) { return MOTION.computeIfAbsent(id, unused -> new Motion()); }

	private static Panel locate(GuiGraphicsExtractor graphics, String id, VisualModule module, float defaultX, float defaultY, int width, int height) {
		int logicalWidth = Math.round(graphics.guiWidth() / hudScale);
		int logicalHeight = Math.round(graphics.guiHeight() / hudScale);
		HudLayout.Position position = HudLayout.position(id, defaultX, defaultY, logicalWidth, logicalHeight);
		int x = Math.clamp(Math.round(position.x() * logicalWidth), 0, Math.max(0, logicalWidth - Math.round(width * position.scale())));
		int y = Math.clamp(Math.round(position.y() * logicalHeight), 0, Math.max(0, logicalHeight - Math.round(height * position.scale())));
		Panel panel = new Panel(id, module.id, module.name, x, y, width, height, hudScale, position.scale());
		PANELS.add(panel);
		return panel;
	}

	/** Starts a panel; {@code background} draws the module's own style behind it. */
	private static Panel begin(GuiGraphicsExtractor graphics, String id, String moduleId, float x, float y, float width, float height, boolean visible, boolean background, boolean editor) {
		VisualModule module = Visuals.module(moduleId);
		Motion state = motion(id);
		state.alpha = editor ? 1 : AstolfoUi.animate(state.alpha, visible ? 1 : 0, 14, seconds);
		state.width = state.width == 0 || editor ? width : AstolfoUi.animate(state.width, width, 15, seconds);
		state.height = state.height == 0 || editor ? height : AstolfoUi.animate(state.height, height, 15, seconds);
		if (state.alpha < 0.005f) return null;
		Panel panel = locate(graphics, id, module, x, y, Math.round(state.width), Math.round(state.height));
		graphics.pose().pushMatrix();
		graphics.pose().translate(panel.x(), panel.y());
		graphics.pose().scale(panel.zoom(), panel.zoom());
		graphics.pose().translate(-panel.x(), -panel.y());
		float pop = editor ? 1 : 0.86f + 0.14f * (1 - (float) Math.pow(1 - state.alpha, 3));
		graphics.pose().translate(panel.x() + panel.width() / 2f, panel.y() + panel.height() / 2f);
		graphics.pose().scale(pop, pop);
		graphics.pose().translate(-panel.x() - panel.width() / 2f, -panel.y() - panel.height() / 2f);
		AstolfoUi.alpha = state.alpha * fade(module, editor);
		if (background) background(graphics, module, panel.x(), panel.y(), panel.width(), panel.height(), 8);
		return panel;
	}

	/** Disabled panels stay visible, dimmed, in the editor so they can still be configured. */
	private static float fade(VisualModule module, boolean editor) { return editor && !module.enabled ? 0.35f : 1; }

	private static void background(GuiGraphicsExtractor graphics, VisualModule module, float x, float y, float width, float height, float radius) {
		String style = module.choice("style");
		if (style.equals("none")) return;
		if (module.bool("shadow")) AstolfoUi.shadow(graphics, x, y, width, height, radius);
		VisualModule look = Visuals.module("Interface");
		if (style.equals("glass")) AstolfoUi.glass(graphics, x, y, width, height, radius, look.number("blur"), Math.round(look.number("tint") * 255) << 24 | 0x0C0C10);
		else AstolfoUi.shape(graphics, x, y, width, height, radius, 0, 0xE6121216, 0xE60C0C10);
		AstolfoUi.shape(graphics, x, y, width, height, radius, 0.8f, 0x1FFFFFFF, 0x08FFFFFF);
	}

	private static void end(GuiGraphicsExtractor graphics) { AstolfoUi.alpha = 1; graphics.pose().popMatrix(); }

	private static void icon(GuiGraphicsExtractor graphics, AstolfoUi.Face face, String glyph, float x, float y, float size) {
		if (!Visuals.module("Interface").bool("accentIcons")) { face.draw(graphics, glyph, x, y, size, Theme.TEXT_SECONDARY); return; }
		AstolfoUi.glow(graphics, x + size / 2, y + size / 2, size / 2, AstolfoUi.ACCENT, 0.12f);
		face.draw(graphics, glyph, x, y, size, AstolfoUi.ACCENT);
	}

	private static void animated(GuiGraphicsExtractor graphics, String id, String value, float x, float y, float size, int color) {
		TEXT.computeIfAbsent(id, unused -> new AnimatedText()).draw(graphics, value, x, y, size, color);
	}

	/** One icon-and-value entry of a single-line panel; a null face means no icon. */
	private record Chip(String key, AstolfoUi.Face face, String glyph, float iconSize, String value) { }

	private static float rowWidth(List<Chip> chips, float textSize) {
		float width = Math.max(0, chips.size() - 1) * CHIP_GAP;
		for (Chip chip : chips) width += (chip.face == null ? 0 : chip.iconSize + 4) + AstolfoUi.SEMIBOLD.width(chip.value, textSize);
		return width;
	}

	private static void drawRow(GuiGraphicsExtractor graphics, Panel panel, List<Chip> chips, float textSize, float x, String prefix) {
		float textY = Paint.centeredTextY(panel.y(), panel.height(), textSize);
		for (int i = 0; i < chips.size(); i++) {
			Chip chip = chips.get(i);
			if (chip.face != null) {
				icon(graphics, chip.face, chip.glyph, x, panel.y() + (panel.height() - chip.iconSize) / 2f, chip.iconSize);
				x += chip.iconSize + 4;
			}
			animated(graphics, prefix + chip.key, chip.value, x, textY, textSize, Theme.TEXT);
			x += AstolfoUi.SEMIBOLD.width(chip.value, textSize);
			if (i < chips.size() - 1) {
				AstolfoUi.shape(graphics, x + CHIP_GAP / 2 - 0.9f, panel.y() + panel.height() / 2f - 0.9f, 1.8f, 1.8f, 0.9f, 0, Theme.TEXT_TERTIARY, Theme.TEXT_TERTIARY);
				x += CHIP_GAP;
			}
		}
	}

	private static void watermark(GuiGraphicsExtractor graphics, boolean editor, int width) {
		Minecraft client = Minecraft.getInstance();
		VisualModule module = Visuals.module("Watermark");
		var info = client.getConnection() == null ? null : client.getConnection().getPlayerInfo(client.player.getUUID());
		List<Chip> chips = new ArrayList<>();
		chips.add(new Chip("title", module.bool("showLogo") ? AstolfoUi.LOGO : null, "A", 14, "ModelMirage"));
		if (module.bool("showPing")) chips.add(new Chip("ping", AstolfoUi.WATERMARK, "D", 9, (info == null ? 0 : info.getLatency()) + " MS"));
		if (module.bool("showFps")) chips.add(new Chip("fps", AstolfoUi.WATERMARK, "C", 9, client.getFps() + " FPS"));
		if (module.bool("showName")) chips.add(new Chip("name", AstolfoUi.WATERMARK, "B", 9, AstolfoUi.SEMIBOLD.fit(client.player.getName().getString(), 8, 80)));
		if (module.bool("showTime")) chips.add(new Chip("time", null, "", 0, LocalTime.now().format(TIME)));
		boolean head = module.bool("showHead");
		float total = 18 + (head ? 22 : 0) + rowWidth(chips, 8);
		Panel panel = begin(graphics, "logo", "Watermark", (width - total) / 2, 10, total, 24, on("Watermark", editor), true, editor);
		if (panel == null) return;
		drawRow(graphics, panel, chips, 8, panel.x() + 9, "watermark");
		if (head) face(graphics, client.player, Math.round(panel.x() + panel.width() - 23), panel.y() + 5, 14);
		end(graphics);
	}

	private static void info(GuiGraphicsExtractor graphics, boolean editor, int height) {
		Minecraft client = Minecraft.getInstance();
		VisualModule module = Visuals.module("Coordinates");
		var pos = client.player.position();
		List<Chip> chips = new ArrayList<>();
		if (module.bool("showCoords")) chips.add(new Chip("coords", AstolfoUi.INTERFACE, "C", 7, (int) pos.x + "X " + (int) pos.y + "Y " + (int) pos.z + "Z"));
		if (module.bool("showSpeed")) chips.add(new Chip("bps", AstolfoUi.INTERFACE, "B", 7, String.format(Locale.ROOT, "%.2f BPS", speed)));
		if (module.bool("showDirection")) chips.add(new Chip("direction", null, "", 0, client.player.getDirection().getName().substring(0, 1).toUpperCase(Locale.ROOT)));
		Panel panel = begin(graphics, "info", "Coordinates", 10, height - 28, 18 + rowWidth(chips, 7.5f), 22, on("Coordinates", editor) && !chips.isEmpty(), true, editor);
		if (panel == null) return;
		drawRow(graphics, panel, chips, 7.5f, panel.x() + 9, "info");
		end(graphics);
	}

	private static void target(GuiGraphicsExtractor graphics, boolean editor, int width, int height) {
		Minecraft client = Minecraft.getInstance();
		VisualModule module = Visuals.module("TargetHud");
		LivingEntity entity = editor ? client.player : target;
		boolean visible = on("TargetHud", editor) && entity != null && entity.isAlive() && (editor || System.currentTimeMillis() - targetTime <= 250);
		boolean head = module.bool("showHead");
		boolean health = module.bool("showHealth");
		boolean armor = module.bool("showArmor");
		float textX = 9 + (head ? 31 : 0);
		float contentHeight = 11 + (health ? 14 : 0) + (armor ? 12 : 0);
		Panel panel = begin(graphics, "target", "TargetHud", width * 0.545f, height * 0.585f, textX + 9 + 88, 18 + Math.max(head ? 24 : 0, contentHeight), visible, true, editor);
		if (panel == null) return;
		if (entity != null) {
			targetHealth = AstolfoUi.animate(targetHealth, entity.getHealth(), 12, seconds);
			damageHealth = AstolfoUi.animate(damageHealth, entity.getHealth(), 6, seconds);
			absorption = AstolfoUi.animate(absorption, entity.getAbsorptionAmount(), 12, seconds);
			if (head) face(graphics, entity, panel.x() + 9, Math.round(panel.y() + (panel.height() - 24) / 2f), 24);
			float left = panel.x() + textX;
			float top = panel.y() + (panel.height() - contentHeight) / 2f;
			AstolfoUi.BOLD.draw(graphics, AstolfoUi.BOLD.fit(entity.getName().getString(), 8.5f, 88), left, top, 8.5f, Theme.TEXT);
			float cursor = top + 11;
			if (health) {
				animated(graphics, "hp", String.format(Locale.ROOT, "%.1f HP", targetHealth), left, cursor, 7, Theme.TEXT_SECONDARY);
				float maximum = Math.max(1, entity.getMaxHealth() + absorption);
				float barY = cursor + 10.5f;
				AstolfoUi.shape(graphics, left, barY, 88, 3.2f, 1.6f, 0, Theme.CONTROL, Theme.CONTROL);
				AstolfoUi.shape(graphics, left, barY, 88 * Math.clamp(damageHealth / maximum, 0, 1), 3.2f, 1.6f, 0, 0xFFEB3C3C, 0xFFEB3C3C);
				float hpWidth = 88 * Math.clamp(targetHealth / maximum, 0, 1);
				AstolfoUi.glow(graphics, left + hpWidth / 2, barY + 1, 2, AstolfoUi.ACCENT, 0.18f);
				AstolfoUi.shape(graphics, left, barY, hpWidth, 3.2f, 1.6f, 0, AstolfoUi.ACCENT, AstolfoUi.mix(AstolfoUi.ACCENT, 0xFFB432FF, 0.3f));
				if (absorption > 0.1f) AstolfoUi.shape(graphics, left + hpWidth, barY, 88 * absorption / maximum, 3.2f, 1.6f, 0, 0xFFFFDC28, 0xFFFFA00A);
				cursor += 14;
			}
			if (armor) {
				EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
				for (int index = 0; index < slots.length; index++) {
					ItemStack item = entity.getItemBySlot(slots[index]);
					if (item.isEmpty()) continue;
					graphics.pose().pushMatrix();
					graphics.pose().translate(left + index * 11, cursor);
					graphics.pose().scale(0.62f, 0.62f);
					graphics.item(item, 0, 0);
					graphics.pose().popMatrix();
				}
			}
		}
		end(graphics);
	}

	private static void face(GuiGraphicsExtractor graphics, LivingEntity entity, int x, int y, int size) {
		if (Visuals.module("Interface").bool("accentIcons")) AstolfoUi.glow(graphics, x + size / 2f, y + size / 2f, size / 2f, AstolfoUi.ACCENT, 0.12f);
		if (entity instanceof AbstractClientPlayer player) {
			Identifier skin = player.getSkin().body().texturePath();
			AstolfoUi.head(graphics, skin, x, y, size, true, entity.hurtTime > 0 ? 0xFFFF8080 : AstolfoUi.TEXT);
		} else {
			Identifier image = Identifier.fromNamespaceAndPath("modelmirage", "astolfo/mob_icon.png");
			AstolfoUi.head(graphics, image, x, y, size, false, AstolfoUi.TEXT);
		}
	}

	private static void effects(GuiGraphicsExtractor graphics, boolean editor, int height) {
		VisualModule module = Visuals.module("Effects");
		List<MobEffectInstance> current = editor ? List.of(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 16500), new MobEffectInstance(MobEffects.STRENGTH, 740, 1), new MobEffectInstance(MobEffects.SPEED, 1640, 1), new MobEffectInstance(MobEffects.ABSORPTION, 2260, 3))
				: new ArrayList<>(Minecraft.getInstance().player.getActiveEffects());
		boolean enabled = on("Effects", editor);
		boolean icons = module.bool("showIcons");
		boolean timer = module.bool("showTimer");
		float textStart = icons ? 28 : 11;
		float fade = fade(module, editor);
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
			float w = textStart + 12 + AstolfoUi.BOLD.width(name + (entry.getValue().getAmplifier() > 0 ? " " + (entry.getValue().getAmplifier() + 1) : ""), 8);
			state.width = state.width == 0 ? w : AstolfoUi.animate(state.width, w, 14, seconds);
			maxWidth = Math.max(maxWidth, state.width);
			totalHeight += state.height;
		}
		if (totalHeight > 1) {
			Panel panel = locate(graphics, "effects", module, 10, (height - totalHeight) / 2, Math.round(maxWidth), Math.max(26, Math.round(totalHeight - 4)));
			graphics.pose().pushMatrix();
			graphics.pose().translate(panel.x(), panel.y());
			graphics.pose().scale(panel.zoom(), panel.zoom());
			graphics.pose().translate(-panel.x(), -panel.y());
			float y = panel.y();
			for (var entry : EFFECTS.entrySet()) {
				Motion state = motion("effect:" + entry.getKey());
				if (state.alpha >= 0.005f) {
					AstolfoUi.alpha = state.alpha * fade;
					float x = panel.x() + (1 - state.alpha) * -12;
					background(graphics, module, x, y, state.width, 26, 8);
					var effect = entry.getValue();
					Identifier key = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value());
					if (icons) {
						Identifier sprite = Identifier.fromNamespaceAndPath(key.getNamespace(), "mob_effect/" + key.getPath());
						graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, Math.round(x + 8), Math.round(y + 6), 14, 14, AstolfoUi.opacity(0xFFFFFFFF, AstolfoUi.alpha));
					}
					String name = Component.translatable(effect.getEffect().value().getDescriptionId()).getString();
					float nameY = timer ? y + 5 : Paint.centeredTextY(y, 26, 8);
					AstolfoUi.BOLD.draw(graphics, name, x + textStart, nameY, 8, Theme.TEXT);
					if (effect.getAmplifier() > 0) AstolfoUi.BOLD.draw(graphics, String.valueOf(effect.getAmplifier() + 1), x + textStart + 2 + AstolfoUi.BOLD.width(name, 8), nameY, 8, AstolfoUi.ACCENT);
					if (timer) {
						String time = effect.isInfiniteDuration() ? "--:--" : effect.getDuration() / 1200 + ":" + String.format(Locale.ROOT, "%02d", effect.getDuration() / 20 % 60);
						animated(graphics, "duration:" + entry.getKey(), time, x + textStart, y + 15, 7, Theme.TEXT_SECONDARY);
					}
				}
				y += state.height;
			}
			AstolfoUi.alpha = 1;
			graphics.pose().popMatrix();
		}
		EFFECTS.entrySet().removeIf(entry -> !active.contains(entry.getKey()) && motion("effect:" + entry.getKey()).alpha < 0.005f);
	}

	private static void inventory(GuiGraphicsExtractor graphics, boolean editor, int width, int height) {
		var inventory = Minecraft.getInstance().player.getInventory();
		boolean stored = false;
		for (int slot = 9; slot < 36 && !stored; slot++) stored = !inventory.getItem(slot).isEmpty();
		// An empty glass block in the middle of the screen is noise; show it only when there is something to see.
		Panel panel = begin(graphics, "inventory", "Inventory", width / 2f - 86, height * 0.70f, 172, 64, on("Inventory", editor) && (editor || stored), true, editor);
		if (panel == null) return;
		for (int slot = 0; slot < 27; slot++) {
			ItemStack item = Minecraft.getInstance().player.getInventory().getItem(slot + 9);
			if (editor && slot < 9 && item.isEmpty()) {
				var examples = List.of(Items.DIAMOND_SWORD, Items.GOLDEN_APPLE, Items.ENDER_PEARL, Items.COBWEB, Items.OBSIDIAN, Items.TOTEM_OF_UNDYING, Items.DIAMOND_PICKAXE, Items.BOW, Items.ARROW);
				item = new ItemStack(examples.get(slot), slot == 0 || slot == 5 || slot == 6 || slot == 7 ? 1 : 64);
			}
			int x = panel.x() + 5 + slot % 9 * 18;
			int y = panel.y() + 5 + slot / 9 * 18;
			graphics.item(item, x, y);
			graphics.itemDecorations(Minecraft.getInstance().font, item, x, y);
		}
		end(graphics);
	}

	private static void armor(GuiGraphicsExtractor graphics, boolean editor, int width, int height) {
		boolean durability = Visuals.module("Armor").bool("showDurability");
		EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		boolean worn = false;
		for (EquipmentSlot slot : slots) worn |= !Minecraft.getInstance().player.getItemBySlot(slot).isEmpty();
		Panel panel = begin(graphics, "armor", "Armor", width / 2f + 10, height - 62, 86, durability ? 31 : 26, on("Armor", editor) && (editor || worn), true, editor);
		if (panel == null) return;
		var examples = List.of(Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS);
		for (int index = 0; index < slots.length; index++) {
			ItemStack item = Minecraft.getInstance().player.getItemBySlot(slots[index]);
			if (editor && item.isEmpty()) item = new ItemStack(examples.get(index));
			int x = panel.x() + 5 + index * 20;
			graphics.item(item, x, panel.y() + 5);
			if (durability && item.isBarVisible()) {
				AstolfoUi.shape(graphics, x + 1, panel.y() + 23, 14, 2, 1, 0, Theme.CONTROL, Theme.CONTROL);
				int color = 0xFF000000 | item.getBarColor();
				AstolfoUi.shape(graphics, x + 1, panel.y() + 23, 14 * item.getBarWidth() / 13f, 2, 1, 0, color, color);
			}
		}
		end(graphics);
	}

	private static void moduleList(GuiGraphicsExtractor graphics, boolean editor, int width) {
		VisualModule panelModule = Visuals.module("ArrayList");
		List<VisualModule> modules = Visuals.modules().stream().filter(module -> !module.isPanel() && !module.category.equals("mascot") && !module.id.equals("Interface"))
				.sorted(Comparator.comparingDouble((VisualModule module) -> AstolfoUi.SEMIBOLD.width(module.name, 7.5f)).reversed()).toList();
		float w = 60;
		float h = 0;
		for (var module : modules) {
			Motion row = motion("module:" + module.id);
			row.alpha = editor ? (module.enabled ? 1 : 0) : AstolfoUi.animate(row.alpha, module.enabled ? 1 : 0, 14, seconds);
			row.height = AstolfoUi.animate(row.height, row.alpha < 0.1f ? 0 : 22, 14, seconds);
			if (row.alpha >= 0.005f) w = Math.max(w, AstolfoUi.SEMIBOLD.width(module.name, 7.5f) + 29);
			h += row.height;
		}
		boolean empty = editor && h < 1;
		Panel panel = begin(graphics, "modules", "ArrayList", width - (empty ? 130 : w) - 15, 210, empty ? 130 : w, empty ? 18 : Math.max(18, h - 4), on("ArrayList", editor) && (editor || h > 1), false, editor);
		if (panel == null) return;
		if (empty) {
			background(graphics, panelModule, panel.x(), panel.y(), panel.width(), panel.height(), 7);
			AstolfoUi.MEDIUM.draw(graphics, "Нет активных модулей", panel.x() + 10, Paint.centeredTextY(panel.y(), panel.height(), 7.5f), 7.5f, Theme.TEXT_TERTIARY);
		}
		float alpha = AstolfoUi.alpha;
		float y = panel.y();
		for (var module : modules) {
			Motion row = motion("module:" + module.id);
			if (row.alpha >= 0.005f) {
				AstolfoUi.alpha = alpha * row.alpha;
				float rowWidth = AstolfoUi.SEMIBOLD.width(module.name, 7.5f) + 29;
				float x = panel.x() + panel.width() - rowWidth + (1 - row.alpha) * 10;
				background(graphics, panelModule, x, y, rowWidth, 18, 7);
				icon(graphics, AstolfoUi.LOGO, "A", x + 6, y + 4, 10);
				AstolfoUi.SEMIBOLD.draw(graphics, module.name, x + 21, Paint.centeredTextY(y, 18, 7.5f), 7.5f, Theme.TEXT);
			}
			y += row.height;
		}
		end(graphics);
	}

	private static void binds(GuiGraphicsExtractor graphics, boolean editor, int width, int height) {
		List<VisualModule> modules = Visuals.modules();
		float w = 110;
		float h = 0;
		for (var module : modules) {
			Motion row = motion("bind:" + module.id);
			boolean visible = module.bind >= 0;
			row.alpha = editor ? (visible ? 1 : 0) : AstolfoUi.animate(row.alpha, visible ? 1 : 0, 14, seconds);
			row.height = editor ? (visible ? 16 : 0) : AstolfoUi.animate(row.height, visible ? 16 : 0, 14, seconds);
			if (row.alpha >= 0.005f) w = Math.max(w, AstolfoUi.MEDIUM.width(module.name, 7.5f) + 56);
			h += row.height;
		}
		if (editor && h < 1) w = Math.max(w, AstolfoUi.MEDIUM.width("Назначь клавишу в настройках", 7.5f) + 20);
		Panel panel = begin(graphics, "binds", "Keybinds", width * 0.30f, height / 3f, w, 38 + Math.max(editor ? 16 : 0, h), on("Keybinds", editor) && (editor || h > 0.1f), true, editor);
		if (panel == null) return;
		icon(graphics, AstolfoUi.INTERFACE, "K", panel.x() + 10, panel.y() + 8, 8);
		AstolfoUi.SEMIBOLD.draw(graphics, "Клавиши", panel.x() + 23, Paint.centeredTextY(panel.y(), 24, 8.5f), 8.5f, Theme.TEXT);
		Paint.separator(graphics, panel.x() + 10, panel.y() + 24, panel.width() - 20);
		float alpha = AstolfoUi.alpha;
		float y = panel.y() + 30;
		if (editor && h < 1) AstolfoUi.MEDIUM.draw(graphics, "Назначь клавишу в настройках", panel.x() + 10, y, 7.5f, Theme.TEXT_TERTIARY);
		for (var module : modules) {
			Motion row = motion("bind:" + module.id);
			if (module.bind >= 0 && row.alpha >= 0.005f) {
				AstolfoUi.alpha = alpha * row.alpha;
				AstolfoUi.MEDIUM.draw(graphics, module.name, panel.x() + 10, y + (1 - row.alpha) * -3, 7.5f, Theme.TEXT);
				String key = AstolfoUi.MEDIUM.fit(dev.modelmirage.client.gui.kit.KeyRow.keyName(module.bind), 7.5f, 40);
				AstolfoUi.MEDIUM.draw(graphics, key, panel.x() + panel.width() - AstolfoUi.MEDIUM.width(key, 7.5f) - 10, y + (1 - row.alpha) * -3, 7.5f, AstolfoUi.ACCENT);
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
		int color = module.bool("useEntityColor") && Minecraft.getInstance().crosshairPickEntity != null ? 0xFFFF7085 : module.color("color");
		AstolfoUi.shape(graphics, x - gap - length, y - thickness / 2, length, thickness, 0, 0, color, color);
		AstolfoUi.shape(graphics, x + gap, y - thickness / 2, length, thickness, 0, 0, color, color);
		AstolfoUi.shape(graphics, x - thickness / 2, y - gap - length, thickness, length, 0, 0, color, color);
		AstolfoUi.shape(graphics, x - thickness / 2, y + gap, thickness, length, 0, 0, color, color);
	}

	private static void boykisser(GuiGraphicsExtractor graphics, boolean editor) {
		Identifier selected = CosmeticSelections.selected(CosmeticSelections.Slot.MASCOT);
		Panel panel = begin(graphics, "boykisser", "BoyKisser", 20, 50, MascotAnimations.width(selected), MascotAnimations.height(selected), on("BoyKisser", editor), false, editor);
		if (panel == null) return;
		MascotAnimations.draw(graphics, selected, panel.x(), panel.y(), panel.width(), panel.height(), AstolfoUi.opacity(0xFFFFFFFF, AstolfoUi.alpha));
		end(graphics);
	}

	public record Panel(String id, String module, String label, int x, int y, int width, int height, float scale, float zoom) {
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
