package dev.modelmirage.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ItemModelOverridesTest {
	private static final Set<String> ITEMS = Set.of("diamond_sword", "mace", "stick");
	private static final Predicate<Identifier> KNOWN = id -> id.getNamespace().equals("minecraft") && ITEMS.contains(id.getPath());

	@TempDir
	Path directory;

	private static Identifier item(String path) {
		return Identifier.withDefaultNamespace(path);
	}

	private static Identifier model(String path) {
		return Identifier.fromNamespaceAndPath("modelmirage", "catalog/" + path);
	}

	private static String config(String entries) {
		return "{\"items\":{" + entries + "}}";
	}

	@Test
	void parsesValidAssignments() {
		Map<Identifier, Identifier> parsed = ItemModelOverrides.parse(
				config("\"minecraft:mace\":\"modelmirage:catalog/axe\",\"stick\":\"modelmirage:catalog/wand-2\""), KNOWN);
		assertEquals(Map.of(item("mace"), model("axe"), item("stick"), model("wand-2")), parsed);
	}

	@Test
	void emptyItemsIsValid() {
		assertTrue(ItemModelOverrides.parse(config(""), KNOWN).isEmpty());
	}

	@Test
	void legacyBloodAxeMaceIdIsMigratedToTheCatalogId() {
		Map<Identifier, Identifier> parsed = ItemModelOverrides.parse(config("\"minecraft:mace\":\"modelmirage:blood_axe_mace\""), KNOWN);
		assertEquals(Map.of(item("mace"), ModelMirageClient.MODEL_ID), parsed);
		assertEquals("catalog/blood-axe-mace", ModelMirageClient.MODEL_ID.getPath());
	}

	@Test
	void parsedMapIsImmutable() {
		Map<Identifier, Identifier> parsed = ItemModelOverrides.parse(config("\"minecraft:mace\":\"modelmirage:catalog/axe\""), KNOWN);
		assertThrows(UnsupportedOperationException.class, () -> parsed.put(item("stick"), model("x")));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"\"minecraft:not_an_item\":\"modelmirage:catalog/axe\"",
			"\"minecraft:mace\":\"minecraft:catalog/axe\"",
			"\"minecraft:mace\":\"modelmirage:axe\"",
			"\"minecraft:mace\":\"modelmirage:catalog/Axe\"",
			"\"minecraft:mace\":\"modelmirage:catalog/-axe\"",
			"\"minecraft:mace\":\"modelmirage:catalog/axe/extra\"",
			"\"minecraft:mace\":\"modelmirage:catalog/../axe\"",
			"\"minecraft:mace\":\"modelmirage:catalog/\"",
			"\"minecraft:mace\":\"Not Valid\"",
			"\"minecraft:mace\":\"\"",
			"\"Bad Key!\":\"modelmirage:catalog/axe\"",
			"\"minecraft:mace\":{\"nested\":1}",
			"\"minecraft:mace\":null"})
	void rejectsUnsupportedEntries(String entry) {
		assertThrows(RuntimeException.class, () -> ItemModelOverrides.parse(config(entry), KNOWN));
	}

	@Test
	void modelIdLengthLimitIsSixtyFourCharacters() {
		assertEquals(1, ItemModelOverrides.parse(config("\"mace\":\"modelmirage:catalog/" + "a".repeat(64) + "\""), KNOWN).size());
		assertThrows(IllegalArgumentException.class,
				() -> ItemModelOverrides.parse(config("\"mace\":\"modelmirage:catalog/" + "a".repeat(65) + "\""), KNOWN));
	}

	@Test
	void oneBadEntryRejectsTheWholeFile() {
		assertThrows(IllegalArgumentException.class, () -> ItemModelOverrides.parse(
				config("\"mace\":\"modelmirage:catalog/axe\",\"nope\":\"modelmirage:catalog/axe\""), KNOWN));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "not json", "[]", "{}", "{\"items\":[]}", "{\"items\":null}", "{\"items\":\"x\"}"})
	void rejectsMalformedDocuments(String json) {
		assertThrows(RuntimeException.class, () -> ItemModelOverrides.parse(json, KNOWN));
	}

	@Test
	void writeThenParseRoundTrips() throws IOException {
		Path file = directory.resolve("config/modelmirage.json");
		Map<Identifier, Identifier> assignments = Map.of(item("mace"), model("axe"), item("diamond_sword"), model("blade"));
		ItemModelOverrides.write(file, assignments);
		assertEquals(assignments, ItemModelOverrides.parse(Files.readString(file, StandardCharsets.UTF_8), KNOWN));
	}

	@Test
	void writeReplacesPreviousContentAndLeavesNoTemporaryFile() throws IOException {
		Path file = directory.resolve("modelmirage.json");
		ItemModelOverrides.write(file, Map.of(item("mace"), model("axe")));
		ItemModelOverrides.write(file, Map.of(item("stick"), model("wand")));
		assertEquals(Map.of(item("stick"), model("wand")), ItemModelOverrides.parse(Files.readString(file), KNOWN));
		try (Stream<Path> files = Files.list(directory)) {
			assertEquals(List.of(file), files.toList());
		}
	}

	@Test
	void writeOfEmptyAssignmentsProducesLoadableFile() throws IOException {
		Path file = directory.resolve("modelmirage.json");
		ItemModelOverrides.write(file, Map.of());
		assertTrue(ItemModelOverrides.parse(Files.readString(file), KNOWN).isEmpty());
	}
}
