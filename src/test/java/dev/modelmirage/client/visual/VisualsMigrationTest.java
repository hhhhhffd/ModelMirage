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

	@Test
	void movesInterfaceSettingsIntoThePreferences() {
		float scale = VisualPreferences.hudScale, tint = VisualPreferences.tint;
		boolean icons = VisualPreferences.accentIcons;
		try {
			Visuals.migrateInterface(json("{\"Interface\":{\"settings\":{\"hudScale\":0.8,\"tint\":0.42,\"accentIcons\":false}}}"));
			assertEquals(0.8f, VisualPreferences.hudScale, 1e-6);
			assertEquals(40, VisualPreferences.tint);
			assertFalse(VisualPreferences.accentIcons);
		} finally {
			VisualPreferences.hudScale = scale;
			VisualPreferences.tint = tint;
			VisualPreferences.accentIcons = icons;
		}
	}

	@Test
	void turnsTheOldDurabilitySwitchIntoTheHiddenChoice() {
		JsonObject off = json("{\"Armor\":{\"settings\":{\"showDurability\":false}}}");
		Visuals.migrateArmor(off);
		assertEquals("hidden", off.getAsJsonObject("Armor").getAsJsonObject("settings").get("durability").getAsString());
		JsonObject on = json("{\"Armor\":{\"settings\":{\"showDurability\":true}}}");
		Visuals.migrateArmor(on);
		assertFalse(on.getAsJsonObject("Armor").getAsJsonObject("settings").has("durability"));
	}

	@Test
	void orderSettingsKeepKnownItemsOnce() {
		VisualModule module = new VisualModule(json("{\"id\":\"W\",\"category\":\"panel\",\"settings\":[{\"id\":\"order\",\"type\":\"order\",\"value\":\"a,b,c\"}]}"));
		module.setting("order").set(new com.google.gson.JsonPrimitive("c,x,a,c"));
		assertEquals(java.util.List.of("c", "a", "b"), module.order("order"));
	}
}
