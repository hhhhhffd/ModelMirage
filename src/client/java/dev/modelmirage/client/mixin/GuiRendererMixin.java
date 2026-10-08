package dev.modelmirage.client.mixin;

import dev.modelmirage.client.gui.Backdrop;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderer.class)
public class GuiRendererMixin {
	// The world is complete and no GUI has been drawn yet: the only safe moment to copy the glass backdrop.
	@Inject(method = "draw", at = @At("HEAD"))
	private void modelmirage$captureBackdrop(CallbackInfo callback) { Backdrop.capture(); }
}
