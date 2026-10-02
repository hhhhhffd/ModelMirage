package dev.modelmirage.client.mixin;

import dev.modelmirage.client.visual.Visuals;
import java.io.IOException;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class KeyboardVisualMixin {
	@Inject(method = "keyPress", at = @At("HEAD"))
	private void modelmirage$bind(long handle, int action, KeyEvent event, CallbackInfo callback) {
		if (action != 1 || Minecraft.getInstance().gui.screen() != null) return;
		boolean changed = false;
		for (var module : Visuals.modules()) {
			if (module.bind >= 0 && module.bind == event.key()) { module.enabled = !module.enabled; changed = true; }
		}
		if (changed) {
			try { Visuals.save(); }
			catch (IOException failure) { LoggerFactory.getLogger("ModelMirage").error("Could not save visual keybind state", failure); }
		}
	}
}
