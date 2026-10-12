package dev.modelmirage.client.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParseException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HudLayoutTest {
	@Test
	void readsPositionsAndDefaultsScaleToOne() {
		Map<String, HudLayout.Position> positions = HudLayout.parse("{\"watermark\":{\"x\":0.25,\"y\":0.5,\"scale\":1.5},\"effects\":{\"x\":0,\"y\":1}}");
		assertEquals(new HudLayout.Position(0.25f, 0.5f, 1.5f), positions.get("watermark"));
		assertEquals(new HudLayout.Position(0, 1, 1), positions.get("effects"));
	}

	@Test
	void clampsPositionIntoTheWindowAndScaleIntoRange() {
		Map<String, HudLayout.Position> positions = HudLayout.parse("{\"a\":{\"x\":-3,\"y\":7,\"scale\":9},\"b\":{\"x\":0.5,\"y\":0.5,\"scale\":0.1}}");
		assertEquals(new HudLayout.Position(0, 1, 2), positions.get("a"));
		assertEquals(0.5f, positions.get("b").scale());
	}

	@Test
	void migratesTheOldMascotPanelId() {
		Map<String, HudLayout.Position> positions = HudLayout.parse("{\"boykisser\":{\"x\":0.1,\"y\":0.2,\"scale\":1.2}}");
		assertEquals(new HudLayout.Position(0.1f, 0.2f, 1.2f), positions.get("mascot"));
		assertFalse(positions.containsKey("boykisser"));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"boykisser\":{\"x\":0.1,\"y\":0.1},\"mascot\":{\"x\":0.9,\"y\":0.9}}",
			"{\"mascot\":{\"x\":0.9,\"y\":0.9},\"boykisser\":{\"x\":0.1,\"y\":0.1}}"})
	void aSavedMascotPositionWinsOverTheOldIdInAnyOrder(String json) {
		assertEquals(new HudLayout.Position(0.9f, 0.9f, 1), HudLayout.parse(json).get("mascot"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"{\"a\":{\"y\":0.5}}", "{\"a\":{\"x\":\"NaN\",\"y\":0.5}}", "{\"a\":{\"x\":0.5,\"y\":0.5,\"scale\":\"Infinity\"}}", "{\"a\":5}", "[]"})
	void rejectsInvalidEntries(String json) {
		assertThrows(RuntimeException.class, () -> HudLayout.parse(json));
	}

	@Test
	void rejectsMalformedJson() {
		assertThrows(JsonParseException.class, () -> HudLayout.parse("{\"a\":"));
	}
}
