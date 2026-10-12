package dev.modelmirage.client.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import net.minecraft.world.entity.EntityEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CombatStatsTest {
	private static final int SELF = 1, ENEMY = 2, OTHER_PLAYER = 3, ZOMBIE = 4, NONE = -1;
	private static final long SECOND = 1_000_000_000L;

	@TempDir
	Path directory;

	@BeforeEach
	void session() {
		CombatSessions.file = directory.resolve("combat.json");
		CombatSessions.forget();
		CombatSessions.enter("test", 0);
	}

	@Test
	void ourLastHitThenDeathIsAPlayerKill() {
		CombatStats.damage(SELF, ENEMY, SELF, true, 0);
		CombatStats.entityEvent(SELF, ENEMY, true, EntityEvent.DEATH, SECOND);
		assertEquals("1/0/0", CombatStats.players());
	}

	@Test
	void fireOrFallAfterOurHitStillCounts() {
		CombatStats.damage(SELF, ENEMY, SELF, true, 0);
		CombatStats.damage(SELF, ENEMY, NONE, false, 2 * SECOND);
		CombatStats.entityEvent(SELF, ENEMY, true, EntityEvent.DEATH, 3 * SECOND);
		assertEquals("1/0/0", CombatStats.players());
	}

	@Test
	void anotherPlayersLaterHitMakesItAnAssist() {
		CombatStats.damage(SELF, ENEMY, SELF, true, 0);
		CombatStats.damage(SELF, ENEMY, OTHER_PLAYER, true, SECOND);
		CombatStats.entityEvent(SELF, ENEMY, true, EntityEvent.DEATH, 2 * SECOND);
		assertEquals("0/0/1", CombatStats.players());
	}

	@Test
	void ourHitOverThirtySecondsAgoIsNoAssist() {
		CombatStats.damage(SELF, ENEMY, SELF, true, 0);
		CombatStats.damage(SELF, ENEMY, OTHER_PLAYER, true, 29 * SECOND);
		CombatStats.entityEvent(SELF, ENEMY, true, EntityEvent.DEATH, 31 * SECOND);
		assertEquals("0/0/0", CombatStats.players());
	}

	@Test
	void aNaturalDeathWithinThirtySecondsIsOurKill() {
		CombatStats.damage(SELF, ZOMBIE, SELF, false, 0);
		CombatStats.damage(SELF, ZOMBIE, NONE, false, 20 * SECOND);
		CombatStats.entityEvent(SELF, ZOMBIE, false, EntityEvent.DEATH, 25 * SECOND);
		assertEquals("1/0/0", CombatStats.mobs());
	}

	@Test
	void totemsPoppedByOthersAfterOurHitAreAssists() {
		CombatStats.damage(SELF, ENEMY, SELF, true, 0);
		CombatStats.damage(SELF, ENEMY, OTHER_PLAYER, true, SECOND);
		CombatStats.entityEvent(SELF, ENEMY, true, EntityEvent.PROTECTED_FROM_DEATH, SECOND);
		assertEquals("0/0/1", CombatStats.totems());
	}

	@Test
	void creditExpiresAfterThirtySeconds() {
		CombatStats.damage(SELF, ENEMY, SELF, true, 0);
		CombatStats.entityEvent(SELF, ENEMY, true, EntityEvent.DEATH, 31 * SECOND);
		assertEquals("0/0/0", CombatStats.players());
	}

	@Test
	void deathsWithoutOurHitDoNotCount() {
		CombatStats.entityEvent(SELF, ENEMY, true, EntityEvent.DEATH, 0);
		assertEquals("0/0/0", CombatStats.players());
	}

	@Test
	void totemsAreCountedBothWaysAndTheTargetCanStillDie() {
		CombatStats.damage(SELF, ENEMY, SELF, true, 0);
		CombatStats.entityEvent(SELF, ENEMY, true, EntityEvent.PROTECTED_FROM_DEATH, 0);
		CombatStats.entityEvent(SELF, SELF, true, EntityEvent.PROTECTED_FROM_DEATH, SECOND);
		CombatStats.damage(SELF, ENEMY, SELF, true, 2 * SECOND);
		CombatStats.entityEvent(SELF, ENEMY, true, EntityEvent.DEATH, 2 * SECOND);
		assertEquals("1/1/0", CombatStats.totems());
		assertEquals("1/0/0", CombatStats.players());
	}

	@Test
	void mobsAndPlayersAreSeparateAndShareOurDeaths() {
		CombatStats.damage(SELF, ZOMBIE, SELF, false, 0);
		CombatStats.entityEvent(SELF, ZOMBIE, false, EntityEvent.DEATH, 0);
		CombatStats.died(SELF, SELF);
		CombatStats.died(SELF, OTHER_PLAYER);
		assertEquals("1/1/0", CombatStats.mobs());
		assertEquals("0/1/0", CombatStats.players());
	}

	@Test
	void ourOwnDamageAndDeathEventsAreNotKills() {
		CombatStats.damage(SELF, SELF, SELF, true, 0);
		CombatStats.entityEvent(SELF, SELF, true, EntityEvent.DEATH, 0);
		assertEquals("0/0/0", CombatStats.players());
	}
}
