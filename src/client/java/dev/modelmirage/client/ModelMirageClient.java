package dev.modelmirage.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public class ModelMirageClient implements ClientModInitializer {
	public static final Identifier MODEL_ID = Identifier.fromNamespaceAndPath("modelmirage", "blood_axe_mace");

	@Override
	public void onInitializeClient() {
		ItemModelOverrides.load();

		var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("modelmirage", "controls"));
		var openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.modelmirage.open",
				InputConstants.Type.KEYSYM,
				InputConstants.KEY_RSHIFT,
				category));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.consumeClick()) {
				if (client.player != null) {
					client.gui.setScreen(new ModelMirageScreen());
				}
			}
		});
	}
}
