package dev.modelmirage.client.mixin;

import dev.modelmirage.client.visual.SystemStats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The server's time updates estimate its TPS and the connection's jitter. HEAD runs first on the network thread, where
 * the arrival time is exact, and again on the client thread after the packet is re-queued; only the first counts.
 */
@Mixin(ClientPacketListener.class)
public class ServerTimeMixin {
	@Inject(method = "handleSetTime", at = @At("HEAD"))
	private void modelmirage$serverTime(ClientboundSetTimePacket packet, CallbackInfo callback) {
		if (!Minecraft.getInstance().isSameThread()) SystemStats.timeUpdate(packet.gameTime(), System.nanoTime());
	}
}
