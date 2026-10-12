package dev.modelmirage.client.mixin;

import dev.modelmirage.client.visual.SystemStats;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The server's time updates estimate its TPS; TAIL runs only on the client thread, after the packet was re-queued there. */
@Mixin(ClientPacketListener.class)
public class ServerTimeMixin {
	@Inject(method = "handleSetTime", at = @At("TAIL"))
	private void modelmirage$serverTime(ClientboundSetTimePacket packet, CallbackInfo callback) {
		SystemStats.timeUpdate(packet.gameTime());
	}
}
