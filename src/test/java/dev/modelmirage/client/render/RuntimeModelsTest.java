package dev.modelmirage.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RuntimeModelsTest {
	private static final Identifier ID = Identifier.fromNamespaceAndPath("modelmirage", "textures/t");

	private static IOException rejected(byte[] png) {
		return assertThrows(IOException.class, () -> RuntimeModels.pngSprite(ID, png));
	}

	@Test
	void decodesValidPngWithItsDimensions() throws IOException {
		try (SpriteContents sprite = RuntimeModels.pngSprite(ID, Fixtures.png(5, 3, 0xFF112233))) {
			assertEquals(5, sprite.width());
			assertEquals(3, sprite.height());
		}
	}

	@ParameterizedTest
	@CsvSource({"2048,1", "1,2048", "1,1"})
	void acceptsDimensionsUpToTheLimit(int width, int height) throws IOException {
		try (SpriteContents sprite = RuntimeModels.pngSprite(ID, Fixtures.png(width, height, 0xFFFFFFFF))) {
			assertEquals(width, sprite.width());
			assertEquals(height, sprite.height());
		}
	}

	@ParameterizedTest
	@CsvSource({"2049,1", "1,2049", "65536,65536", "0,1", "1,0", "-1,1", "1,-1", "2147483647,2147483647"})
	void rejectsOutOfRangeDimensionsFromTheHeaderAlone(int width, int height) {
		// Header-only bytes: if the check did not fire first, decoding would fail with a different message.
		IOException failure = rejected(Fixtures.pngHeader(width, height));
		assertTrue(failure.getMessage().startsWith("Texture dimensions exceed limits"), failure.getMessage());
	}

	@Test
	void rejectsBadSignature() {
		byte[] png = Fixtures.png(2, 2, 0xFF000000);
		png[1] = 'X';
		assertTrue(rejected(png).getMessage().startsWith("Invalid PNG"));
	}

	@Test
	void rejectsBadHeaderChunkType() {
		byte[] png = Fixtures.png(2, 2, 0xFF000000);
		ByteBuffer.wrap(png).putInt(12, 0x49444154);
		assertTrue(rejected(png).getMessage().startsWith("Invalid PNG"));
	}

	@Test
	void rejectsTooShortInput() {
		assertTrue(rejected(new byte[0]).getMessage().startsWith("Invalid PNG"));
		assertTrue(rejected(new byte[32]).getMessage().startsWith("Invalid PNG"));
		byte[] full = Fixtures.png(2, 2, 0xFF000000);
		assertTrue(rejected(java.util.Arrays.copyOf(full, 32)).getMessage().startsWith("Invalid PNG"));
	}

	@Test
	void validHeaderWithCorruptBodyFailsDecoding() {
		byte[] png = Fixtures.pngHeader(16, 16);
		IOException failure = rejected(png);
		assertTrue(!failure.getMessage().startsWith("Texture dimensions exceed limits"), failure.getMessage());
	}
}
