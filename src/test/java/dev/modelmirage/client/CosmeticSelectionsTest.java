package dev.modelmirage.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.modelmirage.client.CosmeticSelections.Slot;
import dev.modelmirage.client.catalog.CatalogModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CosmeticSelectionsTest {
	@TempDir
	Path directory;

	private static Identifier model(String path) {
		return Identifier.fromNamespaceAndPath("modelmirage", "catalog/" + path);
	}

	private Path file(String json) throws IOException {
		Path file = directory.resolve("changer.json");
		Files.writeString(file, json);
		return file;
	}

	@Test
	void writeThenReadRoundTripsEverySlot() throws IOException {
		Map<Slot, Identifier> selections = new EnumMap<>(Slot.class);
		selections.put(Slot.SELF, model("axe"));
		selections.put(Slot.OTHERS, model("blade"));
		selections.put(Slot.MASCOT, model("builtin-mascot"));
		Path file = directory.resolve("nested/modelmirage/changer.json");
		CosmeticSelections.write(file, selections);
		assertEquals(selections, CosmeticSelections.read(file));
	}

	@Test
	void partialSelectionsKeepOtherSlotsUnset() throws IOException {
		Map<Slot, Identifier> loaded = CosmeticSelections.read(file("{\"others\":\"modelmirage:catalog/x\"}"));
		assertEquals(Map.of(Slot.OTHERS, model("x")), loaded);
	}

	@Test
	void unknownKeysAreIgnored() throws IOException {
		assertEquals(Map.of(Slot.SELF, model("x")), CosmeticSelections.read(file("{\"self\":\"modelmirage:catalog/x\",\"future\":\"anything\"}")));
	}

	@Test
	void emptyObjectMeansNoSelections() throws IOException {
		assertTrue(CosmeticSelections.read(file("{}")).isEmpty());
	}

	@Test
	void legacyBuiltinMascotIdIsMigrated() throws IOException {
		Map<Slot, Identifier> loaded = CosmeticSelections.read(file("{\"mascot\":\"modelmirage:catalog/builtin-boykisser\",\"self\":\"modelmirage:catalog/builtin-boykisser\"}"));
		Identifier mascot = CatalogModel.builtinMascot().modelId();
		assertEquals(Map.of(Slot.MASCOT, mascot, Slot.SELF, mascot), loaded);
		assertEquals("catalog/builtin-mascot", mascot.getPath());
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"self\":\"minecraft:catalog/axe\"}",
			"{\"self\":\"modelmirage:axe\"}",
			"{\"self\":\"modelmirage:catalog/Axe\"}",
			"{\"self\":\"modelmirage:catalog/axe/x\"}",
			"{\"self\":\"modelmirage:catalog/\"}",
			"{\"self\":\"modelmirage:catalog/../x\"}",
			"{\"self\":\"Not Valid\"}",
			"{\"self\":\"\"}",
			"{\"self\":{\"a\":1}}",
			"{\"self\":null}",
			"{\"self\":\"modelmirage:catalog/ok\",\"others\":\"modelmirage:catalog/BAD\"}",
			"[]", "not json", ""})
	void rejectsInvalidContent(String json) throws IOException {
		Path file = file(json);
		assertThrows(RuntimeException.class, () -> CosmeticSelections.read(file));
	}

	@Test
	void missingFileFailsWithIoException() {
		assertThrows(NoSuchFileException.class, () -> CosmeticSelections.read(directory.resolve("absent.json")));
	}

	@Test
	void writeReplacesPreviousContentAndLeavesNoTemporaryFile() throws IOException {
		Path file = directory.resolve("changer.json");
		CosmeticSelections.write(file, Map.of(Slot.SELF, model("a")));
		CosmeticSelections.write(file, Map.of(Slot.OTHERS, model("b")));
		assertEquals(Map.of(Slot.OTHERS, model("b")), CosmeticSelections.read(file));
		try (Stream<Path> files = Files.list(directory)) {
			assertEquals(List.of(file), files.toList());
		}
	}

	@Test
	void setRejectsInvalidModelBeforeTouchingDisk() {
		assertThrows(IllegalArgumentException.class, () -> CosmeticSelections.set(Slot.SELF, Identifier.parse("minecraft:stone")));
		assertThrows(IllegalArgumentException.class, () -> CosmeticSelections.set(Slot.SELF, model("a/b")));
		assertNull(CosmeticSelections.selected(Slot.SELF));
	}

	@Test
	void unsetMascotFallsBackToBuiltinAndOthersToNothing() {
		assertEquals(CatalogModel.builtinMascot().modelId(), CosmeticSelections.selected(Slot.MASCOT));
		assertNull(CosmeticSelections.selected(Slot.SELF));
		assertNull(CosmeticSelections.selected(Slot.OTHERS));
		assertTrue(!CosmeticSelections.isSet(Slot.MASCOT));
		assertTrue(!CosmeticSelections.uses(model("axe")));
	}
}
