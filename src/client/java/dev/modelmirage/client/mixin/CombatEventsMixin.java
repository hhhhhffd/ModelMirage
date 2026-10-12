package dev.modelmirage.client.mixin;

import dev.modelmirage.client.visual.CombatStats;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds the session K/D. TAIL runs on the client thread only: the network-thread pass is re-queued before it. */
@Mixin(ClientPacketListener.class)
public abstract class CombatEventsMixin {
	@Inject(method = "handleDamageEvent", at = @At("TAIL"))
	private void modelmirage$damage(ClientboundDamageEventPacket packet, CallbackInfo callback) {
		CombatStats.damage(packet.entityId(), packet.sourceCauseId());
	}

	@Inject(method = "handleEntityEvent", at = @At("TAIL"))
	private void modelmirage$entityEvent(ClientboundEntityEventPacket packet, CallbackInfo callback) {
		var level = ((ClientPacketListener) (Object) this).getLevel();
		if (level != null) CombatStats.entityEvent(packet.getEntity(level), packet.getEventId());
	}

	@Inject(method = "handlePlayerCombatKill", at = @At("TAIL"))
	private void modelmirage$died(ClientboundPlayerCombatKillPacket packet, CallbackInfo callback) {
		CombatStats.died(packet.playerId());
	}

	@Inject(method = "handleLogin", at = @At("TAIL"))
	private void modelmirage$session(ClientboundLoginPacket packet, CallbackInfo callback) {
		CombatStats.joined();
	}
}
