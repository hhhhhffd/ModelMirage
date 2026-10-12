package dev.modelmirage.client.visual;

import dev.modelmirage.client.Lang;
import dev.modelmirage.client.gui.MirageUi;
import dev.modelmirage.client.gui.kit.Paint;
import dev.modelmirage.client.gui.kit.Theme;
import dev.modelmirage.client.CosmeticSelections;
import dev.modelmirage.client.render.MascotAnimations;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
import net.minecraft.client.gui.screens.ChatScreen;
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

/** HUD panels drawn on Minecraft's extraction renderer; adapted parts are listed in THIRD_PARTY_NOTICES.md. */
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
	private static final String HEAD = "head";
	private static final int HEAD_SIZE = 14;
	private static final List<ChipBox> WATERMARK_CHIPS = new ArrayList<>();
	/** Watermark element being dragged to a new place; highlighted while set. */
	static String draggedChip;
	private static float hudScale = 1;
	private static boolean chatOpen;
	/** GUI pixels covered by the chat input line; panels resting on the bottom edge step above it. */
	static final float CHAT_INPUT = 15;
	private VisualHud() { }

	public static void register() {
		HudLayout.load();
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("modelmirage", "visual_hud"), VisualHud::extract);
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, original -> (graphics, delta) -> {
			if (!active("Crosshair")) original.extractRenderState(graphics, delta);
		});
		HudElementRegistry.replaceElement(VanillaHudElements.MOB_EFFECTS, original -> (graphics, delta) -> {
			if (!active("Effects")) original.extractRenderState(graphics, delta);
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
	/** The global HUD switch hands every element back to vanilla. */
	private static boolean active(String module) { return VisualPreferences.hudEnabled && Visuals.enabled(module); }
	private static boolean on(String module, boolean editor) { return editor || Visuals.enabled(module); }

	private static void extract(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || !VisualPreferences.hudEnabled || client.gui.hud.isHidden() || client.gui.screen() instanceof dev.modelmirage.client.ModelMirageScreen) {
			// Hidden panels must not keep stale hit boxes for chat dragging.
			PANELS.clear();
			return;
		}
		// The open chat is the layout editor: every panel shows, with samples and disabled ones dimmed.
		render(graphics, client.gui.screen() instanceof ChatScreen);
	}

	public static void render(GuiGraphicsExtractor graphics, boolean editor) {
		Minecraft client = Minecraft.getInstance();
		PANELS.clear();
		if (client.player == null) return;
		long now = System.nanoTime();
		seconds = Math.clamp((now - frame) / 1e9f, 0.0005f, 0.1f);
		frame = now;
		// Upstream fixes the physical size independently of Minecraft's GUI scale.
		hudScale = (float) (2f / client.getWindow().getGuiScale()) * VisualPreferences.hudScale;
		chatOpen = client.gui.screen() instanceof ChatScreen;
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
		binds(graphics, editor, width, height);
		if (active("Crosshair") && !editor) crosshair(graphics);
		mascot(graphics, editor);
		graphics.pose().popMatrix();
	}

	private static Motion motion(String id) { return MOTION.computeIfAbsent(id, unused -> new Motion()); }

	private static Panel locate(GuiGraphicsExtractor graphics, String id, VisualModule module, float defaultX, float defaultY, int width, int height) {
		int logicalWidth = Math.round(graphics.guiWidth() / hudScale);
		int logicalHeight = Math.round(graphics.guiHeight() / hudScale);
		HudLayout.Position position = HudLayout.get(id);
		float zoom = position == null ? 1 : position.scale();
		// Shares 0 and 1 mean "against the edge", which keeps a small margin from the window border.
		float margin = HudInteraction.MARGIN / hudScale;
		float freeWidth = Math.max(0, logicalWidth - width * zoom - 2 * margin), freeHeight = Math.max(0, logicalHeight - height * zoom - 2 * margin);
		float left, top;
		if (position == null) {
			left = defaultX;
			top = defaultY;
		} else if (position.legacy()) {
			// Shares of the whole window from older layouts become shares of the free space the first time the panel is shown.
			left = position.x() * logicalWidth;
			top = position.y() * logicalHeight;
			HudLayout.place(id, freeWidth > 0 ? (left - margin) / freeWidth : 0, freeHeight > 0 ? (top - margin) / freeHeight : 0);
		} else {
			left = margin + position.x() * freeWidth;
			top = margin + position.y() * freeHeight;
		}
		left = Math.clamp(left, margin, margin + freeWidth);
		top = Math.clamp(top, margin, margin + freeHeight);
		// With the chat open, anything on the bottom edge steps above its input line and drops back afterwards.
		if (chatOpen) top = Math.min(top, Math.max(margin, logicalHeight - CHAT_INPUT / hudScale - height * zoom));
		Panel panel = new Panel(id, module.id, module.name(), Math.round(left), Math.round(top), width, height, hudScale, zoom);
		PANELS.add(panel);
		return panel;
	}
	private static Panel begin(GuiGraphicsExtractor graphics, String id, String moduleId, float x, float y, float width, float height, boolean visible, boolean background, boolean editor) {
		VisualModule module = Visuals.module(moduleId);
		Motion state = motion(id);
		state.alpha = editor ? 1 : MirageUi.animate(state.alpha, visible ? 1 : 0, 14, seconds);
		state.width = state.width == 0 || editor ? width : MirageUi.animate(state.width, width, 15, seconds);
		state.height = state.height == 0 || editor ? height : MirageUi.animate(state.height, height, 15, seconds);
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
		MirageUi.alpha = state.alpha * fade(module, editor);
		if (background) background(graphics, module, panel.x(), panel.y(), panel.width(), panel.height(), 8);
		return panel;
	}

	/** Disabled panels stay visible, dimmed, in the chat editor so they can still be configured. */
	private static float fade(VisualModule module, boolean editor) { return editor && !module.enabled ? 0.35f : 1; }

	private static void background(GuiGraphicsExtractor graphics, VisualModule module, float x, float y, float width, float height, float radius) {
		String style = module.choice("style");
		if (style.equals("none")) return;
		if (module.bool("shadow")) MirageUi.shadow(graphics, x, y, width, height, radius);
		// Each panel follows the global glass unless it has its own.
		boolean own = module.settings.containsKey("customGlass") && module.bool("customGlass");
		float blur = own ? module.number("blur") : VisualPreferences.blur;
		float tint = own ? module.number("tint") : VisualPreferences.tint;
		if (style.equals("glass")) MirageUi.glass(graphics, x, y, width, height, radius, blur, Math.round(tint / 100 * 255) << 24 | 0x0C0C10);
		else MirageUi.shape(graphics, x, y, width, height, radius, 0, 0xE6121216, 0xE60C0C10);
		MirageUi.shape(graphics, x, y, width, height, radius, MirageUi.hairline(graphics), 0x1FFFFFFF, 0x08FFFFFF);
	}

	private static void end(GuiGraphicsExtractor graphics) { MirageUi.alpha = 1; graphics.pose().popMatrix(); }

	private static void icon(GuiGraphicsExtractor graphics, MirageUi.Face face, String glyph, float x, float y, float size) {
		if (!VisualPreferences.accentIcons) { face.draw(graphics, glyph, x, y, size, Theme.TEXT_SECONDARY); return; }
		MirageUi.glow(graphics, x + size / 2, y + size / 2, size / 2, MirageUi.ACCENT, 0.12f);
		face.draw(graphics, glyph, x, y, size, MirageUi.ACCENT);
	}

	private static void animated(GuiGraphicsExtractor graphics, String id, String value, float x, float y, float size, int color) {
		TEXT.computeIfAbsent(id, unused -> new AnimatedText()).draw(graphics, value, x, y, size, color);
	}

	/** One icon-and-value entry of a single-line panel; a null face means no icon. */
	private record Chip(String key, MirageUi.Face face, String glyph, float iconSize, String value) { }

	private static float chipWidth(Chip chip, float textSize) {
		if (chip.key.equals(HEAD)) return HEAD_SIZE;
		return (chip.face == null ? 0 : chip.iconSize + 4) + MirageUi.SEMIBOLD.width(chip.value, textSize);
	}

	private static float rowWidth(List<Chip> chips, float textSize) {
		float width = Math.max(0, chips.size() - 1) * CHIP_GAP;
		for (Chip chip : chips) width += chipWidth(chip, textSize);
		return width;
	}

	private static void drawRow(GuiGraphicsExtractor graphics, Panel panel, List<Chip> chips, float textSize, float x, String prefix, List<ChipBox> boxes) {
		float textY = Paint.centeredTextY(panel.y(), panel.height(), textSize);
		float toScreen = panel.scale() * panel.zoom();
		for (int i = 0; i < chips.size(); i++) {
			Chip chip = chips.get(i);
			float chipWidth = chipWidth(chip, textSize);
			if (boxes != null) {
				boxes.add(new ChipBox(chip.key, panel.screenX() + (x - CHIP_GAP / 2 - panel.x()) * toScreen, panel.screenY(), (chipWidth + CHIP_GAP) * toScreen, panel.screenHeight()));
				if (chip.key.equals(draggedChip)) {
					MirageUi.shape(graphics, x - 4, panel.y() + 3, chipWidth + 8, panel.height() - 6, 6, 0, MirageUi.opacity(MirageUi.ACCENT, 0.18f), MirageUi.opacity(MirageUi.ACCENT, 0.18f));
					MirageUi.shape(graphics, x - 4, panel.y() + 3, chipWidth + 8, panel.height() - 6, 6, 0.8f, MirageUi.ACCENT, MirageUi.ACCENT);
				}
			}
			if (chip.key.equals(HEAD)) {
				face(graphics, Minecraft.getInstance().player, Math.round(x), Math.round(panel.y() + (panel.height() - HEAD_SIZE) / 2f), HEAD_SIZE);
				x += HEAD_SIZE;
			} else if (chip.face != null) {
				icon(graphics, chip.face, chip.glyph, x, panel.y() + (panel.height() - chip.iconSize) / 2f, chip.iconSize);
				x += chip.iconSize + 4;
			}
			if (!chip.key.equals(HEAD)) {
				animated(graphics, prefix + chip.key, chip.value, x, textY, textSize, Theme.TEXT);
				x += MirageUi.SEMIBOLD.width(chip.value, textSize);
			}
			if (i < chips.size() - 1) {
				MirageUi.shape(graphics, x + CHIP_GAP / 2 - 0.9f, panel.y() + panel.height() / 2f - 0.9f, 1.8f, 1.8f, 0.9f, 0, Theme.TEXT_TERTIARY, Theme.TEXT_TERTIARY);
				x += CHIP_GAP;
			}
		}
	}

	private static void watermark(GuiGraphicsExtractor graphics, boolean editor, int width) {
		Minecraft client = Minecraft.getInstance();
		VisualModule module = Visuals.module("Watermark");
		var info = client.getConnection() == null ? null : client.getConnection().getPlayerInfo(client.player.getUUID());
		Map<String, Chip> available = new HashMap<>();
		available.put("title", new Chip("title", module.bool("showLogo") ? MirageUi.LOGO : null, "A", 14, "ModelMirage"));
		if (module.bool("showPing")) available.put("ping", new Chip("ping", MirageUi.WATERMARK, "D", 9, (info == null ? 0 : info.getLatency()) + " MS"));
		if (module.bool("showFps")) available.put("fps", new Chip("fps", MirageUi.WATERMARK, "C", 9, client.getFps() + " FPS"));
		if (module.bool("showName")) available.put("name", new Chip("name", MirageUi.WATERMARK, "B", 9, MirageUi.SEMIBOLD.fit(client.player.getName().getString(), 8, 80)));
		if (module.bool("showTime")) available.put("time", new Chip("time", null, "", 0, LocalTime.now().format(TIME)));
		if (module.bool("showHead")) available.put(HEAD, new Chip(HEAD, null, "", 0, ""));
		List<Chip> chips = new ArrayList<>();
		for (String key : module.order("order")) if (available.containsKey(key)) chips.add(available.get(key));
		float total = 18 + rowWidth(chips, 8);
		WATERMARK_CHIPS.clear();
		Panel panel = begin(graphics, "logo", "Watermark", (width - total) / 2, 10, total, 24, on("Watermark", editor), true, editor);
		if (panel == null) return;
		drawRow(graphics, panel, chips, 8, panel.x() + 9, "watermark", WATERMARK_CHIPS);
		end(graphics);
	}

	private static void info(GuiGraphicsExtractor graphics, boolean editor, int height) {
		Minecraft client = Minecraft.getInstance();
		VisualModule module = Visuals.module("Coordinates");
		var pos = client.player.position();
		List<Chip> chips = new ArrayList<>();
		if (module.bool("showCoords")) chips.add(new Chip("coords", MirageUi.INTERFACE, "C", 7, (int) pos.x + "X " + (int) pos.y + "Y " + (int) pos.z + "Z"));
		if (module.bool("showSpeed")) chips.add(new Chip("bps", MirageUi.INTERFACE, "B", 7, String.format(Locale.ROOT, "%.2f BPS", speed)));
		if (module.bool("showDirection")) chips.add(new Chip("direction", null, "", 0, client.player.getDirection().getName().substring(0, 1).toUpperCase(Locale.ROOT)));
		Panel panel = begin(graphics, "info", "Coordinates", 10, height - 28, 18 + rowWidth(chips, 7.5f), 22, on("Coordinates", editor) && !chips.isEmpty(), true, editor);
		if (panel == null) return;
		drawRow(graphics, panel, chips, 7.5f, panel.x() + 9, "info", null);
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
		// Right of the crosshair, where the inventory sample (bottom centre) cannot reach it.
		Panel panel = begin(graphics, "target", "TargetHud", width / 2f + 24, height / 2f - 24, textX + 9 + 88, 18 + Math.max(head ? 24 : 0, contentHeight), visible, true, editor);
		if (panel == null) return;
		if (entity != null) {
			targetHealth = MirageUi.animate(targetHealth, entity.getHealth(), 12, seconds);
			damageHealth = MirageUi.animate(damageHealth, entity.getHealth(), 6, seconds);
			absorption = MirageUi.animate(absorption, entity.getAbsorptionAmount(), 12, seconds);
			if (head) face(graphics, entity, panel.x() + 9, Math.round(panel.y() + (panel.height() - 24) / 2f), 24);
			float left = panel.x() + textX;
			float top = panel.y() + (panel.height() - contentHeight) / 2f;
			MirageUi.BOLD.draw(graphics, MirageUi.BOLD.fit(entity.getName().getString(), 8.5f, 88), left, top, 8.5f, Theme.TEXT);
			float cursor = top + 11;
			if (health) {
				animated(graphics, "hp", String.format(Locale.ROOT, "%.1f HP", targetHealth), left, cursor, 7, Theme.TEXT_SECONDARY);
				float maximum = Math.max(1, entity.getMaxHealth() + absorption);
				float barY = cursor + 10.5f;
				MirageUi.shape(graphics, left, barY, 88, 3.2f, 1.6f, 0, Theme.CONTROL, Theme.CONTROL);
				MirageUi.shape(graphics, left, barY, 88 * Math.clamp(damageHealth / maximum, 0, 1), 3.2f, 1.6f, 0, 0xFFEB3C3C, 0xFFEB3C3C);
				float hpWidth = 88 * Math.clamp(targetHealth / maximum, 0, 1);
				MirageUi.glow(graphics, left + hpWidth / 2, barY + 1, 2, MirageUi.ACCENT, 0.18f);
				MirageUi.shape(graphics, left, barY, hpWidth, 3.2f, 1.6f, 0, MirageUi.ACCENT, MirageUi.mix(MirageUi.ACCENT, 0xFFB432FF, 0.3f));
				if (absorption > 0.1f) MirageUi.shape(graphics, left + hpWidth, barY, 88 * absorption / maximum, 3.2f, 1.6f, 0, 0xFFFFDC28, 0xFFFFA00A);
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
		if (VisualPreferences.accentIcons) MirageUi.glow(graphics, x + size / 2f, y + size / 2f, size / 2f, MirageUi.ACCENT, 0.12f);
		if (entity instanceof AbstractClientPlayer player) {
			Identifier skin = player.getSkin().body().texturePath();
			MirageUi.head(graphics, skin, x, y, size, true, entity.hurtTime > 0 ? 0xFFFF8080 : MirageUi.TEXT);
		} else {
			Identifier image = Identifier.fromNamespaceAndPath("modelmirage", "ui/mob_icon.png");
			MirageUi.head(graphics, image, x, y, size, false, MirageUi.TEXT);
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
		// Editor samples stay local so they never fade through the real HUD afterwards.
		Map<String, MobEffectInstance> shown = editor ? new LinkedHashMap<>() : EFFECTS;
		for (var effect : current) {
			String id = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()).toString();
			active.add(id); shown.put(id, effect);
		}
		float maxWidth = 80;
		float totalHeight = 0;
		for (var entry : shown.entrySet()) {
			Motion state = motion("effect:" + entry.getKey());
			state.alpha = editor ? 1 : MirageUi.animate(state.alpha, enabled && active.contains(entry.getKey()) ? 1 : 0, 14, seconds);
			state.height = editor ? 30 : MirageUi.animate(state.height, state.alpha < 0.35f ? 0 : 30, 14, seconds);
			String name = Component.translatable(entry.getValue().getEffect().value().getDescriptionId()).getString();
			float w = textStart + 12 + MirageUi.BOLD.width(name + (entry.getValue().getAmplifier() > 0 ? " " + (entry.getValue().getAmplifier() + 1) : ""), 8);
			state.width = state.width == 0 ? w : MirageUi.animate(state.width, w, 14, seconds);
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
			for (var entry : shown.entrySet()) {
				Motion state = motion("effect:" + entry.getKey());
				if (state.alpha >= 0.005f) {
					MirageUi.alpha = state.alpha * fade;
					float x = panel.x() + (1 - state.alpha) * -12;
					background(graphics, module, x, y, state.width, 26, 8);
					var effect = entry.getValue();
					Identifier key = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value());
					if (icons) {
						Identifier sprite = Identifier.fromNamespaceAndPath(key.getNamespace(), "mob_effect/" + key.getPath());
						graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, Math.round(x + 8), Math.round(y + 6), 14, 14, MirageUi.opacity(0xFFFFFFFF, MirageUi.alpha));
					}
					String name = Component.translatable(effect.getEffect().value().getDescriptionId()).getString();
					float nameY = timer ? y + 5 : Paint.centeredTextY(y, 26, 8);
					MirageUi.BOLD.draw(graphics, name, x + textStart, nameY, 8, Theme.TEXT);
					if (effect.getAmplifier() > 0) MirageUi.BOLD.draw(graphics, String.valueOf(effect.getAmplifier() + 1), x + textStart + 2 + MirageUi.BOLD.width(name, 8), nameY, 8, MirageUi.ACCENT);
					if (timer) {
						String time = effect.isInfiniteDuration() ? "--:--" : effect.getDuration() / 1200 + ":" + String.format(Locale.ROOT, "%02d", effect.getDuration() / 20 % 60);
						animated(graphics, "duration:" + entry.getKey(), time, x + textStart, y + 15, 7, Theme.TEXT_SECONDARY);
					}
				}
				y += state.height;
			}
			MirageUi.alpha = 1;
			graphics.pose().popMatrix();
		}
		shown.entrySet().removeIf(entry -> !active.contains(entry.getKey()) && motion("effect:" + entry.getKey()).alpha < 0.005f);
	}

	private static void inventory(GuiGraphicsExtractor graphics, boolean editor, int width, int height) {
		VisualModule module = Visuals.module("Inventory");
		var inventory = Minecraft.getInstance().player.getInventory();
		boolean stored = false;
		for (int slot = 9; slot < 36 && !stored; slot++) stored = !inventory.getItem(slot).isEmpty();
		// "inventory" draws stack counts and durability like the real inventory; "items" only what lies there.
		boolean full = module.choice("display").equals("inventory");
		int pitch = full ? 20 : 18, padding = 4;
		int panelWidth = padding * 2 + 8 * pitch + 16, panelHeight = padding * 2 + 2 * pitch + 16;
		// An empty glass block in the middle of the screen is noise; show it only when there is something to see.
		// The default sits right above the armor panel, which sits above the hotbar.
		Panel panel = begin(graphics, "inventory", "Inventory", (width - panelWidth) / 2f, height - 44 / hudScale - 31 - 8 - panelHeight, panelWidth, panelHeight,
				on("Inventory", editor) && (editor || stored), true, editor);
		if (panel == null) return;
		for (int slot = 0; slot < 27; slot++) {
			ItemStack item = inventory.getItem(slot + 9);
			if (editor && slot < 9 && item.isEmpty()) {
				var examples = List.of(Items.DIAMOND_SWORD, Items.GOLDEN_APPLE, Items.ENDER_PEARL, Items.COBWEB, Items.OBSIDIAN, Items.TOTEM_OF_UNDYING, Items.DIAMOND_PICKAXE, Items.BOW, Items.ARROW);
				item = new ItemStack(examples.get(slot), slot == 0 || slot == 5 || slot == 6 || slot == 7 ? 1 : 64);
				if (slot == 6) item.setDamageValue(item.getMaxDamage() / 3);
			}
			int x = panel.x() + padding + slot % 9 * pitch;
			int y = panel.y() + padding + slot / 9 * pitch;
			graphics.item(item, x, y);
			if (full) graphics.itemDecorations(Minecraft.getInstance().font, item, x, y);
		}
		end(graphics);
	}
	private static void armor(GuiGraphicsExtractor graphics, boolean editor, int width, int height) {
		VisualModule module = Visuals.module("Armor");
		String durability = module.choice("durability");
		boolean percent = durability.equals("percent");
		boolean vertical = module.bool("vertical");
		EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		var examples = List.of(Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS);
		// Only worn pieces are listed, so the panel is as large as the armor; the editor shows a worn sample set.
		List<ItemStack> shown = new ArrayList<>();
		for (int index = 0; index < slots.length; index++) {
			ItemStack item = Minecraft.getInstance().player.getItemBySlot(slots[index]);
			if (editor && item.isEmpty()) {
				item = new ItemStack(examples.get(index));
				item.setDamageValue(item.getMaxDamage() * index / 5);
			}
			if (!item.isEmpty()) shown.add(item);
		}
		int count = Math.max(1, shown.size()), padding = 3, pitch = 18;
		int label = percent ? (vertical ? 22 : 9) : 0;
		int panelWidth = vertical ? padding * 2 + 16 + label : padding * 2 + count * pitch - 2;
		int panelHeight = vertical ? padding * 2 + count * pitch - 2 : padding * 2 + 16 + label;
		// Horizontal: above the hotbar and the health/food rows (44 GUI pixels); vertical: right of the hotbar.
		float defaultX = vertical ? width / 2f + 95 / hudScale : width / 2f + 10;
		float defaultY = vertical ? height - panelHeight - 4 : height - 44 / hudScale - panelHeight;
		Panel panel = begin(graphics, "armor", "Armor", defaultX, defaultY, panelWidth, panelHeight, on("Armor", editor) && !shown.isEmpty(), true, editor);
		if (panel == null) return;
		for (int index = 0; index < shown.size(); index++) {
			ItemStack item = shown.get(index);
			int x = panel.x() + padding + (vertical ? 0 : index * pitch);
			int y = panel.y() + padding + (vertical ? index * pitch : 0);
			graphics.item(item, x, y);
			// Exactly what the inventory slot shows: the vanilla durability bar on the icon.
			if (durability.equals("inventory")) graphics.itemDecorations(Minecraft.getInstance().font, item, x, y);
			if (percent && item.isDamageableItem()) {
				int left = Math.round(100f * (item.getMaxDamage() - item.getDamageValue()) / item.getMaxDamage());
				String text = left + "%";
				int color = 0xFF000000 | item.getBarColor();
				if (vertical) MirageUi.SEMIBOLD.draw(graphics, text, x + 19, Paint.centeredTextY(y, 16, 6.5f), 6.5f, color);
				else MirageUi.SEMIBOLD.draw(graphics, text, x + 8 - MirageUi.SEMIBOLD.width(text, 6) / 2, y + 17, 6, color);
			}
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
			row.alpha = editor ? (visible ? 1 : 0) : MirageUi.animate(row.alpha, visible ? 1 : 0, 14, seconds);
			row.height = editor ? (visible ? 16 : 0) : MirageUi.animate(row.height, visible ? 16 : 0, 14, seconds);
			if (row.alpha >= 0.005f) w = Math.max(w, MirageUi.MEDIUM.width(module.name(), 7.5f) + 56);
			h += row.height;
		}
		if (editor && h < 1) w = Math.max(w, MirageUi.MEDIUM.width(Lang.tr("modelmirage.hud.bind_hint"), 7.5f) + 20);
		Panel panel = begin(graphics, "binds", "Keybinds", width * 0.30f, height / 3f, w, 38 + Math.max(editor ? 16 : 0, h), on("Keybinds", editor) && (editor || h > 0.1f), true, editor);
		if (panel == null) return;
		icon(graphics, MirageUi.INTERFACE, "K", panel.x() + 10, panel.y() + 8, 8);
		MirageUi.SEMIBOLD.draw(graphics, Lang.tr("modelmirage.module.Keybinds"), panel.x() + 23, Paint.centeredTextY(panel.y(), 24, 8.5f), 8.5f, Theme.TEXT);
		Paint.separator(graphics, panel.x() + 10, panel.y() + 24, panel.width() - 20);
		float alpha = MirageUi.alpha;
		float y = panel.y() + 30;
		if (editor && h < 1) MirageUi.MEDIUM.draw(graphics, Lang.tr("modelmirage.hud.bind_hint"), panel.x() + 10, y, 7.5f, Theme.TEXT_TERTIARY);
		for (var module : modules) {
			Motion row = motion("bind:" + module.id);
			if (module.bind >= 0 && row.alpha >= 0.005f) {
				MirageUi.alpha = alpha * row.alpha;
				MirageUi.MEDIUM.draw(graphics, module.name(), panel.x() + 10, y + (1 - row.alpha) * -3, 7.5f, Theme.TEXT);
				String key = MirageUi.MEDIUM.fit(dev.modelmirage.client.gui.kit.KeyRow.keyName(module.bind), 7.5f, 40);
				MirageUi.MEDIUM.draw(graphics, key, panel.x() + panel.width() - MirageUi.MEDIUM.width(key, 7.5f) - 10, y + (1 - row.alpha) * -3, 7.5f, MirageUi.ACCENT);
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
		MirageUi.shape(graphics, x - gap - length, y - thickness / 2, length, thickness, 0, 0, color, color);
		MirageUi.shape(graphics, x + gap, y - thickness / 2, length, thickness, 0, 0, color, color);
		MirageUi.shape(graphics, x - thickness / 2, y - gap - length, thickness, length, 0, 0, color, color);
		MirageUi.shape(graphics, x - thickness / 2, y + gap, thickness, length, 0, 0, color, color);
	}

	private static void mascot(GuiGraphicsExtractor graphics, boolean editor) {
		Identifier selected = CosmeticSelections.selected(CosmeticSelections.Slot.MASCOT);
		Panel panel = begin(graphics, "mascot", "Mascot", 20, 50, MascotAnimations.width(selected), MascotAnimations.height(selected), on("Mascot", editor), false, editor);
		if (panel == null) return;
		MascotAnimations.draw(graphics, selected, panel.x(), panel.y(), panel.width(), panel.height(), MirageUi.opacity(0xFFFFFFFF, MirageUi.alpha));
		end(graphics);
	}

	/** Screen bounds of one watermark element, including half the gap on each side. */
	record ChipBox(String key, float x, float y, float width, float height) {
		boolean contains(double px, double py) { return px >= x && px < x + width && py >= y && py < y + height; }
		float center() { return x + width / 2; }
	}

	static List<ChipBox> watermarkChips() { return List.copyOf(WATERMARK_CHIPS); }

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
					MirageUi.SEMIBOLD.draw(graphics, previous.substring(i, i + 1), x, y - progress * size * 0.5f, size, MirageUi.opacity(color, 1 - progress));
					MirageUi.SEMIBOLD.draw(graphics, glyph, x, y + (1 - progress) * size * 0.5f, size, MirageUi.opacity(color, progress));
				} else MirageUi.SEMIBOLD.draw(graphics, glyph, x, y, size, color);
				x += MirageUi.SEMIBOLD.width(glyph, size);
			}
		}
	}
}
