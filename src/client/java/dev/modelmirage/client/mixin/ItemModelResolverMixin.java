package dev.modelmirage.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.modelmirage.client.ItemModelOverrides;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemModelResolver.class)
public class ItemModelResolverMixin {
	@ModifyExpressionValue(
			method = "appendItemLayers",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;get(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;"))
	private Object modelmirage$selectModel(Object original, ItemStackRenderState state, ItemStack stack) {
		return ItemModelOverrides.resolve(stack, (Identifier) original);
	}
}
