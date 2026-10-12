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
 * Kill/death events from what the server broadcasts to nearby players, so they work on any server,
 * minigames included. A death or totem pop within 30 seconds of our hit is ours when no other player hit it
 * after us, whatever finished it (fire, a fall, a mob); if another player hit it after us, it is an assist.
 *
 * <p>Since 1.19.4 every damage event names its cause (the shooter for arrows, the player who broke an end
 * crystal or lit TNT). Older servers reached through ViaVersion send damage without a cause, so we also credit
 * our own melee hits, damage next to our own projectiles, and damage right after we broke a crystal nearby.
 */
public final class CombatStats {
	/** How long our hit stays ours: a kill if nobody else hits after us, else an assist. */
	private static final long CREDIT_NANOS = 30_000_000_000L;
	/** Entity id → when we last damaged it; dropped when another player hits it after us. */
	private static final Map<Integer, Long> CREDIT = new HashMap<>();
	/** Entity id → when we last damaged it, kept when others hit it too: an assist if someone else finishes it. */
	private static final Map<Integer, Long> HITS = new HashMap<>();
	private static final long CRYSTAL_NANOS = 500_000_000L;
	private static final double PROJECTILE_REACH = 3, CRYSTAL_REACH = 12;
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
		if (causeId == self) { CREDIT.put(entityId, now); HITS.put(entityId, now); }
		else if (causeIsPlayer) CREDIT.remove(entityId);
		// The maps only hold recently hit entities; forget anything past their windows.
		if (CREDIT.size() > 256) CREDIT.values().removeIf(time -> now - time > CREDIT_NANOS);
		if (HITS.size() > 256) HITS.values().removeIf(time -> now - time > CREDIT_NANOS);
	}

	public static void entityEvent(Entity entity, byte event) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || !(entity instanceof LivingEntity)) return;
		entityEvent(client.player.getId(), entity.getId(), entity instanceof Player, event, System.nanoTime());
	}

	static void entityEvent(int self, int entityId, boolean isPlayer, byte event, long now) {
		long millis = System.currentTimeMillis();
		if (entityId == self) {
			if (event == EntityEvent.PROTECTED_FROM_DEATH) CombatSessions.record(session -> session.totemsLost++, millis);
			return;
		}
		Long hit = CREDIT.get(entityId), touched = HITS.get(entityId);
		boolean ours = hit != null && now - hit <= CREDIT_NANOS;
		boolean helped = !ours && touched != null && now - touched <= CREDIT_NANOS;
		if (event == EntityEvent.PROTECTED_FROM_DEATH) {
			if (ours) CombatSessions.record(session -> session.totemsPopped++, millis);
			else if (helped) CombatSessions.record(session -> session.totemAssists++, millis);
		}
		if (event == EntityEvent.DEATH) {
			CREDIT.remove(entityId);
			HITS.remove(entityId);
			if (ours && isPlayer) CombatSessions.record(session -> session.playerKills++, millis);
			else if (ours) CombatSessions.record(session -> session.mobKills++, millis);
			else if (helped && isPlayer) CombatSessions.record(session -> session.pvpAssists++, millis);
			else if (helped) CombatSessions.record(session -> session.pveAssists++, millis);
		}
	}

	/** The server's own death notice for the local player. */
	public static void died(int playerId) {
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) died(client.player.getId(), playerId);
	}

	static void died(int self, int playerId) {
		if (playerId == self) CombatSessions.record(session -> { session.pvpDeaths++; session.pveDeaths++; }, System.currentTimeMillis());
	}

	/** Entity ids and pending crystal belong to one connection; the counts live in {@link CombatSessions}. */
	public static void joined() {
		CREDIT.clear();
		HITS.clear();
		crystal = null;
		CombatSessions.enter(CombatSessions.key(Minecraft.getInstance()), System.currentTimeMillis());
	}

	static String players() { return CombatSessions.format(CombatSessions.Counter.PVP); }
	static String mobs() { return CombatSessions.format(CombatSessions.Counter.PVE); }
	static String totems() { return CombatSessions.format(CombatSessions.Counter.TOTEMS); }
}
