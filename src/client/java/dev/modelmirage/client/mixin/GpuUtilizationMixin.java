package dev.modelmirage.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.modelmirage.client.visual.SystemStats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Minecraft times the GPU only while F3 shows its load; the watermark's GPU element needs the same measurement. */
@Mixin(Minecraft.class)
public class GpuUtilizationMixin {
	@WrapOperation(method = "renderFrame", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/debug/DebugScreenEntryList;isCurrentlyEnabled(Lnet/minecraft/resources/Identifier;)Z"))
	private boolean modelmirage$measureGpu(DebugScreenEntryList entries, Identifier id, Operation<Boolean> original) {
		return original.call(entries, id) || id.equals(DebugScreenEntries.GPU_UTILIZATION) && SystemStats.gpuWanted();
	}
}
