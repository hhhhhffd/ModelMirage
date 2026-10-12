package dev.modelmirage.client.visual;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class HudSnapTest {
	private static final float[][] NONE = {};

	@Test
	void snapsToTheWindowMarginsAndCentre() {
		assertArrayEquals(new float[] {HudInteraction.MARGIN, HudInteraction.MARGIN}, HudInteraction.snapAxis(6, 50, 400, NONE, false));
		assertArrayEquals(new float[] {175, 200}, HudInteraction.snapAxis(178, 50, 400, NONE, false));
		assertArrayEquals(new float[] {346, 396}, HudInteraction.snapAxis(343, 50, 400, NONE, false));
	}

	@Test
	void snapsToAnotherPanelsEdgesCentreAndSides() {
		float[][] other = {{100, 60}};
		assertArrayEquals(new float[] {100, 100}, HudInteraction.snapAxis(102, 20, 400, other, false));
		assertArrayEquals(new float[] {140, 160}, HudInteraction.snapAxis(138, 20, 400, other, false));
		assertArrayEquals(new float[] {120, 130}, HudInteraction.snapAxis(121, 20, 400, other, false));
		assertArrayEquals(new float[] {164, 162}, HudInteraction.snapAxis(167, 20, 400, other, false));
		assertArrayEquals(new float[] {76, 98}, HudInteraction.snapAxis(74, 20, 400, other, false));
	}

	@Test
	void theNearestCandidateWins() {
		float[][] other = {{100, 60}};
		// 143 is 3 from the end-aligned spot (140) and further from every other candidate.
		assertArrayEquals(new float[] {140, 160}, HudInteraction.snapAxis(143, 20, 400, other, false));
	}

	@Test
	void outsideSnapReachTheGridAppliesOnlyInTheEditor() {
		assertArrayEquals(new float[] {56, -1}, HudInteraction.snapAxis(57.5f, 10, 400, NONE, true));
		assertArrayEquals(new float[] {57.5f, -1}, HudInteraction.snapAxis(57.5f, 10, 400, NONE, false));
	}
}
