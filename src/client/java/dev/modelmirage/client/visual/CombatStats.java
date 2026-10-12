package dev.modelmirage.client.visual;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/**
 * Session kill/death counts from what the server broadcasts to nearby players, so they work on any server,
 * minigames included. Like vanilla kill credit, a death or totem pop counts for us when we were the last
 * player to damage that entity within five seconds, even if fire or a fall finished it.
 *
 * <p>Since 1.19.4 every damage event names its cause (the shooter for arrows, the player who broke an end
 * crystal or lit TNT). Older servers reached through ViaVersion send damage without a cause, so we also credit
 * our own melee hits, damage next to our own projectiles, and damage right after we broke a crystal nearby.
 */
public final class CombatStats {
	private static final long CREDIT_NANOS = 5_000_000_000L;
	/** Entity id → when we last damaged it; dropped when another player hits it after us. */
	private static final Map<Integer, Long> CREDIT = new HashMap<>();
	private static final long CRYSTAL_NANOS = 500_000_000L;
	private static final double PROJECTILE_REACH = 3, CRYSTAL_REACH = 12;
	private static int playerKills, mobKills, deaths, totemsPopped, totemsLost;
	private static Vec3 crystal;
	private static long crystalTime;

	private CombatStats() { }

	/** A damage event: {@code causeId} is the responsible entity, -1 for none (fire, fall, void). */
	public static void damage(int entityId, int causeId) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.level == null) return;
		long now = System.nanoTime();
		int cause = causeId;
		Entity target = client.level.getEntity(entityId);
		if (cause < 0 && target != null && ourUnattributedDamage(client, target, now)) cause = client.player.getId();
		damage(client.player.getId(), entityId, cause, client.level.getEntity(cause) instanceof Player, now);
	}

	/** Fallback for causeless damage: one of our projectiles is next to the target, or we just broke a crystal near it. */
	private static boolean ourUnattributedDamage(Minecraft client, Entity target, long now) {
		var around = target.getBoundingBox().inflate(PROJECTILE_REACH);
		if (!client.level.getEntitiesOfClass(Projectile.class, around, projectile -> projectile.getOwner() == client.player).isEmpty()) return true;
		return crystal != null && now - crystalTime <= CRYSTAL_NANOS && target.position().distanceTo(crystal) <= CRYSTAL_REACH;
	}

	/** Our own swing: credit right away (any server version), and remember crystals for their explosion. */
	public static void attacked(Entity target) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) return;
		long now = System.nanoTime();
		if (target instanceof EndCrystal) { crystal = target.position(); crystalTime = now; return; }
		damage(client.player.getId(), target.getId(), client.player.getId(), true, now);
	}

	static void damage(int self, int entityId, int causeId, boolean causeIsPlayer, long now) {
		if (entityId == self) return;
		if (causeId == self) CREDIT.put(entityId, now);
		else if (causeIsPlayer) CREDIT.remove(entityId);
		// The map only holds recently hit entities; forget anything past the credit window.
		if (CREDIT.size() > 256) CREDIT.values().removeIf(time -> now - time > CREDIT_NANOS);
	}

	public static void entityEvent(Entity entity, byte event) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || !(entity instanceof LivingEntity)) return;
		entityEvent(client.player.getId(), entity.getId(), entity instanceof Player, event, System.nanoTime());
	}

	static void entityEvent(int self, int entityId, boolean isPlayer, byte event, long now) {
		if (entityId == self) {
			if (event == EntityEvent.PROTECTED_FROM_DEATH) totemsLost++;
			return;
		}
		Long hit = CREDIT.get(entityId);
		boolean ours = hit != null && now - hit <= CREDIT_NANOS;
		if (event == EntityEvent.PROTECTED_FROM_DEATH && ours) totemsPopped++;
		if (event == EntityEvent.DEATH) {
			CREDIT.remove(entityId);
			if (!ours) return;
			if (isPlayer) playerKills++;
			else mobKills++;
		}
	}

	/** The server's own death notice for the local player. */
	public static void died(int playerId) {
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) died(client.player.getId(), playerId);
	}

	static void died(int self, int playerId) { if (playerId == self) deaths++; }

	/** A new connection or server switch starts a new session. */
	public static void reset() {
		CREDIT.clear();
		crystal = null;
		playerKills = mobKills = deaths = totemsPopped = totemsLost = 0;
	}

	static String players() { return playerKills + "/" + deaths; }
	static String mobs() { return mobKills + "/" + deaths; }
	static String totems() { return totemsPopped + "/" + totemsLost; }
}
