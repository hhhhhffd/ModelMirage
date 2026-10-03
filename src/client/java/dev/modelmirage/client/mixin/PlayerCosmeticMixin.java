package dev.modelmirage.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.modelmirage.client.CosmeticSelections;
import dev.modelmirage.client.render.RuntimeModels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces client-side player geometry while retaining the vanilla name/leash submission. */
@Mixin(LivingEntityRenderer.class)
public abstract class PlayerCosmeticMixin<T extends LivingEntity, S extends LivingEntityRenderState> extends EntityRenderer<T, S> {
	protected PlayerCosmeticMixin(EntityRendererProvider.Context context) { super(context); }
	@Shadow protected abstract void setupRotations(S state, PoseStack pose, float rotation, float scale);

	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"), cancellable = true)
	private void modelmirage$player(S state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo callback) {
		if (!(state instanceof AvatarRenderState avatar) || state.entityType != EntityTypes.PLAYER || avatar.isSpectator || state.isInvisible) return;
		var player = Minecraft.getInstance().player;
		if (player == null) return;
		var slot = avatar.id == player.getId() ? CosmeticSelections.Slot.SELF : CosmeticSelections.Slot.OTHERS;
		var selected = CosmeticSelections.selected(slot);
		if (selected == null || !RuntimeModels.contains(selected)) return;
		pose.pushPose();
		setupRotations(state, pose, state.bodyRot, state.scale);
		RuntimeModels.submitPlayer(selected, pose, collector, state.lightCoords, LivingEntityRenderer.getOverlayCoords(state, 0), state.outlineColor, 1.8f * state.scale);
		pose.popPose();
		super.submit(state, pose, collector, camera);
		callback.cancel();
	}
}
