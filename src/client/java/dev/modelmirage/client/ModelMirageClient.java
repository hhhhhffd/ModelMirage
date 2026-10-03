package dev.modelmirage.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import dev.modelmirage.client.catalog.CatalogService;
import dev.modelmirage.client.render.RuntimeModels;
import dev.modelmirage.client.render.MascotAnimations;
import dev.modelmirage.client.visual.Visuals;
import dev.modelmirage.client.visual.VisualHud;
import dev.modelmirage.client.visual.VisualPreferences;
import dev.modelmirage.client.visual.HudInteraction;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screens.ChatScreen;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public class ModelMirageClient implements ClientModInitializer {
	public static final Identifier MODEL_ID = Identifier.fromNamespaceAndPath("modelmirage", "catalog/blood-axe-mace");
	private boolean catalogStarted;

	@Override
	public void onInitializeClient() {
		ItemModelOverrides.load();
		CosmeticSelections.load();
		Visuals.load();
		VisualPreferences.load();
		VisualHud.register();
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof ChatScreen)) return;
			HudInteraction interaction = new HudInteraction();
			ScreenMouseEvents.allowMouseClick(screen).register((current, event) -> !interaction.click(event, false, current.width, current.height));
			ScreenMouseEvents.allowMouseDrag(screen).register((current, event, dx, dy) -> !interaction.drag(event, current.width, current.height));
			ScreenMouseEvents.allowMouseRelease(screen).register((current, event) -> !interaction.release(event));
			ScreenMouseEvents.allowMouseScroll(screen).register((current, x, y, horizontal, vertical) -> !interaction.scroll(x, y, vertical, current.width, current.height));
			ScreenEvents.afterExtract(screen).register((current, graphics, mouseX, mouseY, delta) -> interaction.draw(graphics, mouseX, mouseY, false));
			ScreenEvents.remove(screen).register(current -> interaction.save());
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			CatalogService.close();
			RuntimeModels.close();
			MascotAnimations.close();
		});

		var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("modelmirage", "controls"));
		var openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.modelmirage.open",
				InputConstants.Type.KEYSYM,
				InputConstants.KEY_RSHIFT,
				category));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			VisualHud.tick();
			// Runtime atlases need the shaders from Minecraft's initial resource load.
			if (!catalogStarted && client.isGameLoadFinished()) {
				catalogStarted = true;
				CatalogService.refresh();
			}
			while (openKey.consumeClick()) {
				if (client.player != null) {
					client.gui.setScreen(new ModelMirageScreen());
				}
			}
		});
	}
}
