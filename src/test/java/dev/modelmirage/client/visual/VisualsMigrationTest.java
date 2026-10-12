package dev.modelmirage.client.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class VisualsMigrationTest {
	private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }

	@Test
	void copiesTheOldMascotModule() {
		JsonObject config = json("{\"BoyKisser\":{\"enabled\":false,\"bind\":75}}");
		Visuals.migrateMascot(config);
		assertEquals(json("{\"enabled\":false,\"bind\":75}"), config.getAsJsonObject("Mascot"));
	}

	@Test
	void keepsAnAlreadySavedMascotModule() {
		JsonObject config = json("{\"BoyKisser\":{\"enabled\":false},\"Mascot\":{\"enabled\":true}}");
		Visuals.migrateMascot(config);
		assertEquals(json("{\"enabled\":true}"), config.getAsJsonObject("Mascot"));
	}

	@Test
	void leavesConfigsWithoutTheOldModuleAlone() {
		JsonObject config = json("{\"Watermark\":{\"enabled\":true}}");
		Visuals.migrateMascot(config);
		assertFalse(config.has("Mascot"));
	}
}
