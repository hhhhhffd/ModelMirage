package dev.modelmirage.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import dev.modelmirage.client.catalog.CatalogService;
import dev.modelmirage.client.render.RuntimeModels;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public class ModelMirageClient implements ClientModInitializer {
	public static final Identifier MODEL_ID = Identifier.fromNamespaceAndPath("modelmirage", "catalog/blood-axe-mace");
	private boolean catalogStarted;

	@Override
	public void onInitializeClient() {
		ItemModelOverrides.load();
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			CatalogService.close();
			RuntimeModels.close();
		});

		var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("modelmirage", "controls"));
		var openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.modelmirage.open",
				InputConstants.Type.KEYSYM,
				InputConstants.KEY_RSHIFT,
				category));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
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
