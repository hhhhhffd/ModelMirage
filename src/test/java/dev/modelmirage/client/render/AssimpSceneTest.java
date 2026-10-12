package dev.modelmirage.client.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AssimpSceneTest {
	private static final String TRIANGLE_OBJ = """
			v 0 0 0
			v 1 0 0
			v 0 1 0
			vt 0.25 0.25
			vt 0.75 0.25
			vt 0.25 0.75
			f 1/1 2/2 3/3
			""";

	private static AssimpScene.Scene read(String extension, String text, Map<String, byte[]> sideFiles) throws IOException {
		return AssimpScene.read(extension, Fixtures.utf8(text), sideFiles);
	}

	private static AssimpScene.Material materialOfFirstTriangle(AssimpScene.Scene scene) {
		return scene.materials().get(scene.materialOf()[0]);
	}

	@Test
	void objWithoutMaterialLibraryDecodesGeometryAndUvs() throws IOException {
		AssimpScene.Scene scene = read("obj", TRIANGLE_OBJ, Map.of());
		assertEquals(1, scene.triangles());
		assertEquals(9, scene.positions().length);
		float[] expectedPositions = {0, 0, 0, 1, 0, 0, 0, 1, 0};
		float[] sorted = scene.positions().clone();
		// Triangulation may rotate the corner order; the vertex set must be the same.
		java.util.Arrays.sort(sorted);
		java.util.Arrays.sort(expectedPositions);
		assertArrayEquals(expectedPositions, sorted);
		assertEquals(6, scene.uvs().length);
		// v grows downwards in the result (FlipUVs): vt (0.25, 0.25) becomes (0.25, 0.75).
		boolean found = false;
		for (int corner = 0; corner < 3; corner++) {
			if (scene.positions()[corner * 3] == 0 && scene.positions()[corner * 3 + 1] == 0) {
				assertEquals(0.25f, scene.uvs()[corner * 2], 1e-6f);
				assertEquals(0.75f, scene.uvs()[corner * 2 + 1], 1e-6f);
				found = true;
			}
		}
		assertTrue(found);
		AssimpScene.Material material = materialOfFirstTriangle(scene);
		assertNull(material.texture());
		assertEquals(0xFF, material.color() >>> 24);
		assertTrue(scene.embedded().isEmpty());
	}

	@Test
	void objWithoutTextureCoordinatesGetsZeroUvs() throws IOException {
		AssimpScene.Scene scene = read("obj", "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n", Map.of());
		assertArrayEquals(new float[6], scene.uvs());
	}

	@Test
	void quadsAreTriangulated() throws IOException {
		AssimpScene.Scene scene = read("obj", "v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nf 1 2 3 4\n", Map.of());
		assertEquals(2, scene.triangles());
		assertEquals(18, scene.positions().length);
		assertEquals(12, scene.uvs().length);
		assertEquals(2, scene.materialOf().length);
	}

	@Test
	void polygonsWithManyCornersBecomeTriangleFans() throws IOException {
		AssimpScene.Scene scene = read("obj", "v 0 0 0\nv 2 0 0\nv 3 1 0\nv 2 2 0\nv 0 2 0\nf 1 2 3 4 5\n", Map.of());
		assertEquals(3, scene.triangles());
	}

	@Test
	void mtlProvidesTextureReferenceColorAndOpacity() throws IOException {
		String mtl = """
				newmtl red
				Kd 1 0 0
				d 0.5
				map_Kd Skin.PNG
				""";
		// The side file is registered under a path with different case than the mtllib statement.
		AssimpScene.Scene scene = read("obj", "mtllib MODEL.MTL\n" + TRIANGLE_OBJ.replace("f 1/1", "usemtl red\nf 1/1"),
				Map.of("textures/Model.mtl", Fixtures.utf8(mtl)));
		AssimpScene.Material material = materialOfFirstTriangle(scene);
		assertEquals("Skin.PNG", material.texture());
		assertEquals(0xFF, material.color() >> 16 & 0xFF);
		assertEquals(0, material.color() >> 8 & 0xFF);
		assertEquals(0, material.color() & 0xFF);
		assertEquals(128, material.color() >>> 24, "d 0.5 scales the alpha channel");
	}

	@Test
	void fullyOpaqueMtlKeepsOpaqueAlpha() throws IOException {
		AssimpScene.Scene scene = read("obj", "mtllib m.mtl\nusemtl blue\n" + TRIANGLE_OBJ,
				Map.of("m.mtl", Fixtures.utf8("newmtl blue\nKd 0 0 1\nd 1.0\n")));
		assertEquals(0xFF0000FF, materialOfFirstTriangle(scene).color());
	}

	@Test
	void missingMaterialLibraryStillLoadsGeometry() throws IOException {
		AssimpScene.Scene scene = read("obj", "mtllib absent.mtl\nusemtl x\n" + TRIANGLE_OBJ, Map.of());
		assertEquals(1, scene.triangles());
	}

	@Test
	void materialsAreAssignedPerTriangle() throws IOException {
		String obj = """
				mtllib m.mtl
				v 0 0 0
				v 1 0 0
				v 0 1 0
				v 1 1 0
				usemtl a
				f 1 2 3
				usemtl b
				f 2 4 3
				""";
		AssimpScene.Scene scene = read("obj", obj, Map.of("m.mtl", Fixtures.utf8("newmtl a\nKd 1 0 0\nnewmtl b\nKd 0 1 0\n")));
		assertEquals(2, scene.triangles());
		assertEquals(0xFFFF0000, scene.materials().get(scene.materialOf()[0]).color());
		assertEquals(0xFF00FF00, scene.materials().get(scene.materialOf()[1]).color());
	}

	@Test
	void glbEmbedsTextureAndReferencesItByIndex() throws IOException {
		byte[] png = Fixtures.png(2, 2, 0xFF336699);
		AssimpScene.Scene scene = AssimpScene.read("glb", Fixtures.glb(Fixtures.TRIANGLE, Fixtures.TRIANGLE_UVS, png), Map.of());
		assertEquals(1, scene.triangles());
		assertEquals("*0", materialOfFirstTriangle(scene).texture());
		assertEquals(1, scene.embedded().size());
		AssimpScene.Embedded embedded = scene.embedded().get(0);
		assertNotNull(embedded.encoded());
		assertNull(embedded.argb());
		assertArrayEquals(png, embedded.encoded());
	}

	@Test
	void glbWithoutTextureHasNoReference() throws IOException {
		AssimpScene.Scene scene = AssimpScene.read("glb", Fixtures.glb(Fixtures.TRIANGLE, Fixtures.TRIANGLE_UVS, null), Map.of());
		assertNull(materialOfFirstTriangle(scene).texture());
		assertTrue(scene.embedded().isEmpty());
	}

	@Test
	void gltfWithDataUriLoadsWithoutSideFiles() throws IOException {
		AssimpScene.Scene scene = AssimpScene.read("gltf", Fixtures.gltfWithDataUri(Fixtures.TRIANGLE), Map.of());
		assertEquals(1, scene.triangles());
	}

	@Test
	void gltfReadsExternalBufferFromSideFilesByBasename() throws IOException {
		byte[] buffer = Fixtures.floats(Fixtures.TRIANGLE);
		AssimpScene.Scene scene = AssimpScene.read("gltf", Fixtures.gltfWithExternalBuffer("Geometry.BIN"), Map.of("buffers/geometry.bin", buffer));
		assertEquals(1, scene.triangles());
	}

	@ParameterizedTest
	@ValueSource(strings = {"/etc/passwd", "../x.bin", "../../etc/passwd", "file:///etc/passwd", "C:/Windows/win.ini"})
	void gltfBufferOutsideTheCatalogFilesFailsWithoutReadingDisk(String uri) {
		assertThrows(IOException.class, () -> AssimpScene.read("gltf", Fixtures.gltfWithExternalBuffer(uri), Map.of()));
	}

	@Test
	void sideFilesOnlyServeTheirOwnNames() {
		byte[] unrelated = Fixtures.floats(Fixtures.TRIANGLE);
		assertThrows(IOException.class,
				() -> AssimpScene.read("gltf", Fixtures.gltfWithExternalBuffer("/etc/passwd"), Map.of("other.bin", unrelated)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"obj", "fbx", "gltf", "glb", "dae", "3ds", "ply", "stl", "blend"})
	void garbageIsRejectedForEveryFormat(String extension) {
		byte[] garbage = new byte[4096];
		new Random(42).nextBytes(garbage);
		assertThrows(IOException.class, () -> AssimpScene.read(extension, garbage, Map.of()));
	}

	@Test
	void emptyInputIsRejected() {
		assertThrows(IOException.class, () -> AssimpScene.read("obj", new byte[0], Map.of()));
		assertThrows(IOException.class, () -> AssimpScene.read("glb", new byte[0], Map.of()));
	}

	@Test
	void truncatedGlbIsRejected() {
		byte[] glb = Fixtures.glb(Fixtures.TRIANGLE, Fixtures.TRIANGLE_UVS, null);
		byte[] truncated = java.util.Arrays.copyOf(glb, glb.length - 20);
		assertThrows(IOException.class, () -> AssimpScene.read("glb", truncated, Map.of()));
	}

	@Test
	void sceneWithoutTrianglesIsRejected() {
		IOException failure = assertThrows(IOException.class, () -> read("obj", "v 0 0 0\nv 1 0 0\nl 1 2\n", Map.of()));
		assertNotNull(failure.getMessage());
	}

	@Test
	void triangleLimitIsInclusive() throws IOException {
		assertEquals(AssimpScene.MAX_TRIANGLES, read("obj", Fixtures.objStrip(AssimpScene.MAX_TRIANGLES), Map.of()).triangles());
		IOException failure = assertThrows(IOException.class, () -> read("obj", Fixtures.objStrip(AssimpScene.MAX_TRIANGLES + 1), Map.of()));
		assertTrue(failure.getMessage().contains("triangles"), failure.getMessage());
	}

	@ParameterizedTest
	@ValueSource(floats = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
	void nonFiniteVertexPositionsAreRejected(float bad) {
		float[] positions = {0, 0, 0, 1, 0, 0, bad, 1, 0};
		assertThrows(IOException.class, () -> AssimpScene.read("glb", Fixtures.glb(positions, Fixtures.TRIANGLE_UVS, null), Map.of()));
	}

	@Test
	void basenameStripsDirectoriesOfBothStylesAndLowercases() {
		assertEquals("tex.png", AssimpScene.basename("Textures/Sub\\TEX.PNG"));
		assertEquals("a", AssimpScene.basename("A"));
		assertEquals("", AssimpScene.basename("dir/"));
		assertEquals("passwd", AssimpScene.basename("/etc/passwd"));
		assertEquals("x.bin", AssimpScene.basename("../x.bin"));
	}
}
