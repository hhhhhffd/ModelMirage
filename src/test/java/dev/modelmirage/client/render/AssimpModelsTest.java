package dev.modelmirage.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.modelmirage.client.render.AssimpModels.TextureSource;
import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AssimpModelsTest {
	private static final float EPSILON = 1e-5f;

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath("modelmirage", path);
	}

	private static float[] extent(float[] positions, int axis, boolean max) {
		float value = max ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
		for (int i = axis; i < positions.length; i += 3) value = max ? Math.max(value, positions[i]) : Math.min(value, positions[i]);
		return new float[] {value};
	}

	private static float size(float[] positions, int axis) {
		return extent(positions, axis, true)[0] - extent(positions, axis, false)[0];
	}

	private static float center(float[] positions, int axis) {
		return (extent(positions, axis, true)[0] + extent(positions, axis, false)[0]) / 2;
	}

	@Test
	void formatsMapCatalogNamesToImporterExtensions() {
		assertEquals(Set.of("obj", "fbx", "gltf", "glb", "dae", "3ds", "ply", "stl", "blend", "blender"), AssimpModels.FORMATS.keySet());
		assertEquals("blend", AssimpModels.FORMATS.get("blend"));
		assertEquals("blend", AssimpModels.FORMATS.get("blender"));
		AssimpModels.FORMATS.forEach((format, extension) -> {
			if (!format.equals("blender")) assertEquals(format, extension);
		});
	}

	@Test
	void everyFormatMatchesCatalogFormatPattern() {
		AssimpModels.FORMATS.keySet().forEach(format -> assertTrue(format.matches("[a-z0-9][a-z0-9_-]{0,63}"), format));
	}

	@Test
	void normalizedFitsLongestAxisIntoUnitCubeAndCentersIt() {
		float[] normalized = AssimpModels.normalized(new float[] {10, 20, 30, 14, 22, 31, 12, 21, 30.5f});
		assertEquals(1f, size(normalized, 0), EPSILON, "x is the longest axis");
		assertEquals(0.5f, size(normalized, 1), EPSILON);
		assertEquals(0.25f, size(normalized, 2), EPSILON);
		for (int axis = 0; axis < 3; axis++) assertEquals(0.5f, center(normalized, axis), EPSILON);
	}

	@Test
	void normalizedPreservesAspectRatioAndRelativePlacement() {
		float[] source = {0, 0, 0, 2, 4, 1, 1, 2, 0.5f};
		float[] normalized = AssimpModels.normalized(source);
		assertEquals(1f, size(normalized, 1), EPSILON, "y is the longest axis");
		assertEquals(0.5f, size(normalized, 0), EPSILON);
		// The third vertex was the exact midpoint of the box and must land on the centre.
		assertEquals(0.5f, normalized[6], EPSILON);
		assertEquals(0.5f, normalized[7], EPSILON);
		assertEquals(0.5f, normalized[8], EPSILON);
	}

	@Test
	void normalizedHandlesNegativeAndHugeCoordinates() {
		float[] normalized = AssimpModels.normalized(new float[] {-1e6f, -3e6f, 5e5f, 1e6f, 3e6f, -5e5f});
		assertEquals(1f, size(normalized, 1), 1e-4f);
		for (int axis = 0; axis < 3; axis++) assertEquals(0.5f, center(normalized, axis), 1e-4f);
		for (float value : normalized) assertTrue(value >= -1e-4f && value <= 1 + 1e-4f);
	}

	@Test
	void normalizedDoesNotMutateItsInputAndKeepsLength() {
		float[] source = {1, 2, 3, 4, 5, 6, 7, 8, 9};
		float[] copy = source.clone();
		float[] normalized = AssimpModels.normalized(source);
		assertEquals(source.length, normalized.length);
		assertTrue(java.util.Arrays.equals(copy, source));
	}

	@Test
	void normalizedDegeneratePointStaysFiniteAtTheCentre() {
		float[] normalized = AssimpModels.normalized(new float[] {7, 7, 7, 7, 7, 7, 7, 7, 7});
		for (float value : normalized) assertEquals(0.5f, value, EPSILON);
	}

	@Test
	void normalizedIsIdempotent() {
		Random random = new Random(7);
		float[] positions = new float[90];
		for (int i = 0; i < positions.length; i++) positions[i] = (random.nextFloat() - 0.3f) * 50;
		float[] once = AssimpModels.normalized(positions);
		float[] twice = AssimpModels.normalized(once);
		for (int i = 0; i < once.length; i++) assertEquals(once[i], twice[i], 1e-4f);
		assertEquals(1f, Math.max(size(once, 0), Math.max(size(once, 1), size(once, 2))), EPSILON);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"tex.png|tex", "a/b/Tex.PNG|tex", "dir\\Name.tga|name", "noext|noext", "a.b.c|a.b",
			".hidden|.hidden", "textures/skin|skin", "UPPER.Png|upper"})
	void stemIsLowercaseFileNameWithoutLastExtension(String path, String expected) {
		assertEquals(expected, AssimpModels.stem(path));
	}

	@Test
	void embeddedReferenceByStarIndex() throws IOException {
		assertEquals(new TextureSource(1, null), AssimpModels.resolveTexture("*1", List.of("a.png", "b.png"), Set.of(), false));
	}

	@Test
	void embeddedReferenceByNameIgnoresDirectoriesAndCase() throws IOException {
		TextureSource source = AssimpModels.resolveTexture("C:\\Models\\Skin.PNG", List.of("other.png", "skin.png"), Set.of(id("textures/skin.png")), false);
		assertEquals(new TextureSource(1, null), source, "an embedded match wins over a catalog file of the same stem");
	}

	@Test
	void outOfRangeEmbeddedIndexFallsBackToCatalogTextures() throws IOException {
		Set<Identifier> catalog = Set.of(id("textures/a.png"));
		assertEquals(new TextureSource(-1, id("textures/a.png")), AssimpModels.resolveTexture("*5", List.of("x.png"), catalog, false));
	}

	@Test
	void invalidEmbeddedIndexIsAnError() {
		assertThrows(IOException.class, () -> AssimpModels.resolveTexture("*abc", List.of("a"), Set.of(), false));
		assertThrows(IOException.class, () -> AssimpModels.resolveTexture("*", List.of("a"), Set.of(), false));
	}

	@Test
	void catalogTextureIsMatchedByFileStem() throws IOException {
		List<Identifier> catalog = List.of(id("textures/body.png"), id("textures/head.png"));
		assertEquals(new TextureSource(-1, id("textures/head.png")), AssimpModels.resolveTexture("maps/Head.TGA", List.of(), catalog, false));
	}

	@Test
	void loneCatalogTextureServesAnyUnmatchedReference() throws IOException {
		List<Identifier> catalog = List.of(id("textures/only.png"));
		assertEquals(new TextureSource(-1, id("textures/only.png")), AssimpModels.resolveTexture("whatever.jpg", List.of(), catalog, false));
	}

	@Test
	void unmatchedReferenceWithSeveralCatalogTexturesBecomesSolidColor() throws IOException {
		List<Identifier> catalog = List.of(id("textures/a.png"), id("textures/b.png"));
		assertEquals(TextureSource.NONE, AssimpModels.resolveTexture("c.png", List.of(), catalog, false));
		assertEquals(TextureSource.NONE, AssimpModels.resolveTexture("c.png", List.of(), List.of(), false));
	}

	@Test
	void materialWithoutTextureUsesLoneCatalogTextureOnlyWhenWholeFileHasNone() throws IOException {
		List<Identifier> catalog = List.of(id("textures/only.png"));
		assertEquals(new TextureSource(-1, id("textures/only.png")), AssimpModels.resolveTexture(null, List.of(), catalog, true));
		assertEquals(TextureSource.NONE, AssimpModels.resolveTexture(null, List.of(), catalog, false));
	}

	@Test
	void materialWithoutTextureIgnoresSeveralCatalogTextures() throws IOException {
		assertEquals(TextureSource.NONE, AssimpModels.resolveTexture(null, List.of(), List.of(id("textures/a.png"), id("textures/b.png")), true));
		assertEquals(TextureSource.NONE, AssimpModels.resolveTexture(null, List.of(), List.of(), true));
	}

	@Test
	void noneHasNoSource() {
		assertEquals(-1, TextureSource.NONE.embedded());
		assertNull(TextureSource.NONE.catalog());
	}
}
