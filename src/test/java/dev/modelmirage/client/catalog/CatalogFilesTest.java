package dev.modelmirage.client.catalog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogFilesTest {
	private static final byte[] DATA = "hello catalog".getBytes(StandardCharsets.UTF_8);
	private static final CatalogModel.Asset ASSET = new CatalogModel.Asset("a.bin", CatalogFiles.sha256(DATA), DATA.length);

	@TempDir
	Path directory;

	@Test
	void sha256MatchesKnownVectors() {
		assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", CatalogFiles.sha256("abc".getBytes(StandardCharsets.US_ASCII)));
		assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", CatalogFiles.sha256(new byte[0]));
	}

	@Test
	void verifiedReturnsMatchingData() throws IOException {
		assertArrayEquals(DATA, CatalogFiles.verified(ASSET, DATA));
	}

	@Test
	void verifiedRejectsWrongChecksumOrLength() {
		byte[] sameLength = DATA.clone();
		sameLength[0] ^= 1;
		assertThrows(IOException.class, () -> CatalogFiles.verified(ASSET, sameLength));
		assertThrows(IOException.class, () -> CatalogFiles.verified(ASSET, new byte[0]));
		assertThrows(IOException.class, () -> CatalogFiles.verified(ASSET, (new String(DATA, StandardCharsets.UTF_8) + "!").getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void readVerifiedReturnsNullForMissingObject() throws IOException {
		assertNull(CatalogFiles.readVerified(directory.resolve("missing"), ASSET));
	}

	@Test
	void readVerifiedReturnsIntactObject() throws IOException {
		Path object = directory.resolve("object");
		Files.write(object, DATA);
		assertArrayEquals(DATA, CatalogFiles.readVerified(object, ASSET));
	}

	@Test
	void readVerifiedDiscardsWrongSizeAndCorruptObjects() throws IOException {
		Path object = directory.resolve("object");
		Files.write(object, new byte[] {1, 2, 3});
		assertNull(CatalogFiles.readVerified(object, ASSET));
		byte[] corrupt = DATA.clone();
		corrupt[corrupt.length - 1] ^= 0x55;
		Files.write(object, corrupt);
		assertNull(CatalogFiles.readVerified(object, ASSET));
	}

	@Test
	void writeCreatesParentsAndLeavesNoTemporaryFiles() throws IOException {
		Path target = directory.resolve("cache/objects/" + ASSET.sha256());
		CatalogFiles.write(target, DATA);
		assertArrayEquals(DATA, Files.readAllBytes(target));
		try (Stream<Path> siblings = Files.list(target.getParent())) {
			assertEquals(List.of(target), siblings.toList());
		}
	}

	@Test
	void writeReplacesExistingObject() throws IOException {
		Path target = directory.resolve("object");
		Files.write(target, new byte[] {9, 9, 9, 9});
		CatalogFiles.write(target, DATA);
		assertArrayEquals(DATA, Files.readAllBytes(target));
		assertTrue(CatalogFiles.readVerified(target, ASSET) != null);
	}

	@Test
	void presentChecksExistenceAndSizeOnly() throws IOException {
		Path object = directory.resolve(ASSET.sha256());
		assertFalse(CatalogFiles.present(object, ASSET));
		Files.write(object, DATA);
		assertTrue(CatalogFiles.present(object, ASSET));
		Files.write(object, new byte[DATA.length + 1]);
		assertFalse(CatalogFiles.present(object, ASSET));
		Files.delete(object);
		Files.createDirectory(object);
		assertFalse(CatalogFiles.present(object, ASSET));
	}

	@Test
	void pruneDeletesOnlyUnreferencedObjects() throws IOException {
		Path objects = directory.resolve("objects");
		Files.createDirectories(objects);
		Files.write(objects.resolve("keep"), DATA);
		Files.write(objects.resolve("stale"), new byte[10]);
		Files.write(objects.resolve("old"), new byte[5]);
		assertEquals(15, CatalogFiles.prune(objects, Set.of("keep")));
		try (Stream<Path> left = Files.list(objects)) {
			assertEquals(List.of("keep"), left.map(path -> path.getFileName().toString()).toList());
		}
	}

	@Test
	void pruneIgnoresAMissingDirectory() throws IOException {
		assertEquals(0, CatalogFiles.prune(directory.resolve("missing"), Set.of()));
	}

	@Test
	void entrySizeSumsEveryFile() {
		var texture = new CatalogModel.Asset("t.png", "a".repeat(64), 300);
		var parent = new CatalogModel.Asset("p.json", "b".repeat(64), 20);
		var entry = new CatalogModel("x", "X", CatalogModel.Kind.ITEM, "glb", "", 0, List.of(), ASSET,
				Map.of(Identifier.parse("modelmirage:x/t"), texture), Map.of(Identifier.parse("modelmirage:x/p"), parent));
		assertEquals(DATA.length + 320, entry.bytes());
		assertEquals(3, entry.assets().size());
		assertEquals(0, CatalogModel.builtinMascot().bytes());
	}
}
