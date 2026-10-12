package dev.modelmirage.client.render;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.modelmirage.client.catalog.CatalogModel;
import dev.modelmirage.client.catalog.ModelLoader;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MascotAnimationsTest {
	private static final Identifier A = Identifier.fromNamespaceAndPath("modelmirage", "frames/a");
	private static final Identifier B = Identifier.fromNamespaceAndPath("modelmirage", "frames/b");
	private static final Map<Identifier, byte[]> FRAMES = Map.of(A, Fixtures.png(2, 2, 0xFFFF0000), B, Fixtures.png(3, 1, 0xFF00FF00));

	private static String definition(int width, int height, int interval, List<String> frames) {
		return "{\"width\":%d,\"height\":%d,\"frame_time_ms\":%d,\"frames\":[%s]}".formatted(width, height, interval,
				String.join(",", frames.stream().map(frame -> "\"" + frame + "\"").toList()));
	}

	private static ModelLoader.Prepared prepare(String definition, Map<Identifier, byte[]> textures) throws IOException {
		return MascotAnimations.prepare(CatalogModel.builtinMascot(), Fixtures.utf8(definition), textures, Map.of());
	}

	private static void accept(String definition) {
		ModelLoader.Prepared prepared = assertDoesNotThrow(() -> prepare(definition, FRAMES));
		prepared.discard();
	}

	@Test
	void acceptsValidDefinitionAndDiscardReleasesImages() {
		accept(definition(64, 64, 100, List.of("modelmirage:frames/a", "modelmirage:frames/b", "modelmirage:frames/a")));
	}

	@ParameterizedTest
	@CsvSource({"8,8,16", "512,512,2000", "8,512,16", "512,8,2000"})
	void acceptsBoundaryValues(int width, int height, int interval) {
		accept(definition(width, height, interval, List.of("modelmirage:frames/a")));
	}

	@ParameterizedTest
	@CsvSource({"7,64,100", "513,64,100", "64,7,100", "64,513,100", "0,64,100", "-8,64,100", "64,64,15", "64,64,2001", "64,64,0", "64,64,-100"})
	void rejectsOutOfRangeDimensionsAndTiming(int width, int height, int interval) {
		assertThrows(IOException.class, () -> prepare(definition(width, height, interval, List.of("modelmirage:frames/a")), FRAMES));
	}

	@Test
	void frameCountLimitIsTwoHundredFiftySix() {
		accept(definition(64, 64, 100, Collections.nCopies(256, "modelmirage:frames/a")));
		assertThrows(IOException.class,
				() -> prepare(definition(64, 64, 100, Collections.nCopies(257, "modelmirage:frames/a")), FRAMES));
	}

	@Test
	void rejectsEmptyFrameList() {
		assertThrows(IOException.class, () -> prepare(definition(64, 64, 100, List.of()), FRAMES));
	}

	@Test
	void framesMustBeListedTextures() {
		assertThrows(IOException.class, () -> prepare(definition(64, 64, 100, List.of("modelmirage:frames/a", "modelmirage:frames/missing")), FRAMES));
	}

	@Test
	void framesMustBeValidPngs() {
		Map<Identifier, byte[]> broken = Map.of(A, new byte[] {1, 2, 3});
		assertThrows(IOException.class, () -> prepare(definition(64, 64, 100, List.of("modelmirage:frames/a")), broken));
		Map<Identifier, byte[]> badSignature = Map.of(A, Fixtures.pngHeader(2, 2));
		badSignature.get(A)[0] = 0;
		assertThrows(IOException.class, () -> prepare(definition(64, 64, 100, List.of("modelmirage:frames/a")), badSignature));
	}

	@Test
	void rejectsOversizedFrameTexturesBeforeDecoding() {
		Map<Identifier, byte[]> huge = Map.of(A, Fixtures.pngHeader(2049, 1));
		IOException failure = assertThrows(IOException.class, () -> prepare(definition(64, 64, 100, List.of("modelmirage:frames/a")), huge));
		assertTrue(failure.getMessage().contains("exceed limits"), failure.getMessage());
	}

	@Test
	void rejectsMalformedDefinitions() {
		assertThrows(RuntimeException.class, () -> prepare("not json", FRAMES));
		assertThrows(RuntimeException.class, () -> prepare("{}", FRAMES));
		assertThrows(RuntimeException.class, () -> prepare("{\"width\":64,\"height\":64,\"frame_time_ms\":100}", FRAMES));
		assertThrows(RuntimeException.class, () -> prepare("{\"width\":64,\"height\":64,\"frame_time_ms\":100,\"frames\":[\"Bad Id\"]}", FRAMES));
	}
}
