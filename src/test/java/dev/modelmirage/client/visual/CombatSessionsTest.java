package dev.modelmirage.client.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.modelmirage.client.visual.CombatSessions.Counter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CombatSessionsTest {
	private static final long MINUTE = 60_000L;
	@TempDir
	Path directory;

	@BeforeEach
	void fresh() {
		CombatSessions.file = directory.resolve("combat.json");
		CombatSessions.forget();
	}

	private static void kill(long now) { CombatSessions.record(session -> session.playerKills++, now); }

	@Test
	void rejoiningWithinFifteenMinutesContinues() {
		CombatSessions.enter("mc.example.net", 0);
		kill(MINUTE);
		CombatSessions.leave(2 * MINUTE);
		CombatSessions.enter("mc.example.net", 16 * MINUTE);
		assertEquals("1/0/0", CombatSessions.format(Counter.PVP));
	}

	@Test
	void aLongerBreakStartsFromZero() {
		CombatSessions.enter("mc.example.net", 0);
		kill(MINUTE);
		CombatSessions.leave(2 * MINUTE);
		CombatSessions.enter("mc.example.net", 18 * MINUTE);
		assertEquals("0/0/0", CombatSessions.format(Counter.PVP));
	}

	@Test
	void eachServerKeepsItsOwnCounts() {
		CombatSessions.enter("a.net", 0);
		kill(0);
		CombatSessions.leave(MINUTE);
		CombatSessions.enter("b.net", MINUTE);
		assertEquals("0/0/0", CombatSessions.format(Counter.PVP));
		CombatSessions.leave(2 * MINUTE);
		CombatSessions.enter("a.net", 3 * MINUTE);
		assertEquals("1/0/0", CombatSessions.format(Counter.PVP));
	}

	@Test
	void aCrashKeepsTheSessionThroughTheSavedFile() {
		CombatSessions.enter("a.net", 0);
		kill(MINUTE);
		CombatSessions.tick(3 * MINUTE);
		CombatSessions.forget();
		CombatSessions.enter("a.net", 10 * MINUTE);
		assertEquals("1/0/0", CombatSessions.format(Counter.PVP));
	}

	@Test
	void playingKeepsTheSessionAliveBeyondFifteenMinutes() {
		CombatSessions.enter("a.net", 0);
		kill(0);
		for (long minute = 1; minute <= 40; minute++) CombatSessions.tick(minute * MINUTE);
		CombatSessions.forget();
		CombatSessions.enter("a.net", 45 * MINUTE);
		assertEquals("1/0/0", CombatSessions.format(Counter.PVP));
	}

	@Test
	void resettingOneCounterKeepsTheOthers() {
		CombatSessions.enter("a.net", 0);
		CombatSessions.record(session -> { session.playerKills = 3; session.pvpDeaths = 1; session.mobKills = 7; session.pveDeaths = 1; session.totemsPopped = 2; }, 0);
		CombatSessions.reset(Counter.PVP);
		assertEquals("0/0/0", CombatSessions.format(Counter.PVP));
		assertEquals("7/1/0", CombatSessions.format(Counter.PVE));
		assertEquals("2/0/0", CombatSessions.format(Counter.TOTEMS));
	}

	@Test
	void expiredSessionsAreDroppedFromTheFile() throws IOException {
		CombatSessions.enter("old.net", 0);
		CombatSessions.leave(0);
		CombatSessions.enter("new.net", 20 * MINUTE);
		String saved = Files.readString(CombatSessions.file);
		assertTrue(saved.contains("new.net"));
		assertFalse(saved.contains("old.net"));
	}

	@Test
	void aDamagedFileStartsFresh() throws IOException {
		Files.writeString(CombatSessions.file, "{\"a.net\": 5");
		CombatSessions.enter("a.net", 0);
		assertEquals("0/0/0", CombatSessions.format(Counter.PVP));
	}

	@Test
	void nothingCountsOutsideASession() {
		kill(0);
		assertEquals("0/0/0", CombatSessions.format(Counter.PVP));
	}
}
