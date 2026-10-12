package dev.modelmirage.client.catalog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
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
}
