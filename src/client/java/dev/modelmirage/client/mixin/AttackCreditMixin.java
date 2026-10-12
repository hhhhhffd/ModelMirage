package dev.modelmirage.client.mixin;

import dev.modelmirage.client.visual.CombatStats;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Our melee hits, known on the client for every server version; old servers do not say who caused damage. */
@Mixin(MultiPlayerGameMode.class)
public class AttackCreditMixin {
	@Inject(method = "attack", at = @At("HEAD"))
	private void modelmirage$attack(Player player, Entity target, CallbackInfo callback) {
		CombatStats.attacked(target);
	}
}
