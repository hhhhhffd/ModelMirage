package dev.modelmirage.client.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.entity.EntityEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CombatStatsTest {
	private static final int SELF = 1, ENEMY = 2, OTHER_PLAYER = 3, ZOMBIE = 4, NONE = -1;
	private static final long SECOND = 1_000_000_000L;

	@BeforeEach
	void reset() { CombatStats.reset(); }

	@Test
	void ourLastHitThenDeathIsAPlayerKill() {
		CombatStats.damage(SELF, ENEMY, SELF, true, 0);
		CombatStats.entityEvent(SELF, ENEMY, true, EntityEvent.DEATH, SECOND);
		assertEquals("1/0", CombatStats.players());
	}

	@Test
	void fireOrFallAfterOurHitStillCounts() {
		CombatStats.damage(SELF, ENEMY, SELF, true, 0);
		CombatStats.damage(SELF, ENEMY, NONE, false, 2 * SECOND);
		CombatStats.entityEvent(SELF, ENEMY, true, EntityEvent.DEATH, 3 * SECOND);
		assertEquals("1/0", CombatStats.players());
	}

	@Test
	void anotherPlayersLaterHitTakesTheCredit() {
		CombatStats.damage(SELF, ENEMY, SELF, true, 0);
		CombatStats.damage(SELF, ENEMY, OTHER_PLAYER, true, SECOND);
		CombatStats.entityEvent(SELF, ENEMY, true, EntityEvent.DEATH, 2 * SECOND);
		assertEquals("0/0", CombatStats.players());
	}

	@Test
	void creditExpiresAfterFiveSeconds() {
		CombatStats.damage(SELF, ENEMY, SELF, true, 0);
		CombatStats.entityEvent(SELF, ENEMY, true, EntityEvent.DEATH, 6 * SECOND);
		assertEquals("0/0", CombatStats.players());
	}

	@Test
	void deathsWithoutOurHitDoNotCount() {
		CombatStats.entityEvent(SELF, ENEMY, true, EntityEvent.DEATH, 0);
		assertEquals("0/0", CombatStats.players());
	}

	@Test
	void totemsAreCountedBothWaysAndTheTargetCanStillDie() {
		CombatStats.damage(SELF, ENEMY, SELF, true, 0);
		CombatStats.entityEvent(SELF, ENEMY, true, EntityEvent.PROTECTED_FROM_DEATH, 0);
		CombatStats.entityEvent(SELF, SELF, true, EntityEvent.PROTECTED_FROM_DEATH, SECOND);
		CombatStats.damage(SELF, ENEMY, SELF, true, 2 * SECOND);
		CombatStats.entityEvent(SELF, ENEMY, true, EntityEvent.DEATH, 2 * SECOND);
		assertEquals("1/1", CombatStats.totems());
		assertEquals("1/0", CombatStats.players());
	}

	@Test
	void mobsAndPlayersAreSeparateAndShareOurDeaths() {
		CombatStats.damage(SELF, ZOMBIE, SELF, false, 0);
		CombatStats.entityEvent(SELF, ZOMBIE, false, EntityEvent.DEATH, 0);
		CombatStats.died(SELF, SELF);
		CombatStats.died(SELF, OTHER_PLAYER);
		assertEquals("1/1", CombatStats.mobs());
		assertEquals("0/1", CombatStats.players());
	}

	@Test
	void ourOwnDamageAndDeathEventsAreNotKills() {
		CombatStats.damage(SELF, SELF, SELF, true, 0);
		CombatStats.entityEvent(SELF, SELF, true, EntityEvent.DEATH, 0);
		assertEquals("0/0", CombatStats.players());
	}
}
