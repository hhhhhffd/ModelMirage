package dev.modelmirage.client.render;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.imageio.ImageIO;

/** Generates model and image files in memory so no binary fixtures are checked in. */
final class Fixtures {
	static final long PNG_SIGNATURE = 0x89504E470D0A1A0AL;

	private Fixtures() { }

	/** A real, decodable PNG filled with one colour. */
	static byte[] png(int width, int height, int argb) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) image.setRGB(x, y, argb);
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			if (!ImageIO.write(image, "png", out)) throw new IllegalStateException("No PNG writer");
		} catch (IOException impossible) {
			throw new IllegalStateException(impossible);
		}
		return out.toByteArray();
	}

	/** Only signature and IHDR dimensions are valid, which is all the header checks look at. */
	static byte[] pngHeader(int width, int height) {
		ByteBuffer buffer = ByteBuffer.allocate(64);
		buffer.putLong(PNG_SIGNATURE).putInt(13).putInt(0x49484452).putInt(width).putInt(height);
		return buffer.array();
	}

	static byte[] utf8(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	/** A strip of {@code triangles} triangles in the XY plane as OBJ text. */
	static String objStrip(int triangles) {
		StringBuilder obj = new StringBuilder();
		for (int i = 0; i < triangles + 2; i++) obj.append("v ").append(i).append(' ').append(i % 2).append(" 0\n");
		for (int i = 1; i <= triangles; i++) obj.append("f ").append(i).append(' ').append(i + 1).append(' ').append(i + 2).append('\n');
		return obj.toString();
	}

	static final float[] TRIANGLE = {0, 0, 0, 1, 0, 0, 0, 1, 0};
	static final float[] TRIANGLE_UVS = {0, 0, 1, 0, 0, 1};

	/** A one-triangle GLB; {@code png}, when not null, is embedded and used as the base colour texture. */
	static byte[] glb(float[] positions, float[] uvs, byte[] png) {
		ByteArrayOutputStream binary = new ByteArrayOutputStream();
		binary.writeBytes(floats(positions));
		binary.writeBytes(floats(uvs));
		int imageOffset = binary.size();
		if (png != null) {
			binary.writeBytes(png);
			while (binary.size() % 4 != 0) binary.write(0);
		}
		String json = """
				{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
				 "meshes":[{"primitives":[{"attributes":{"POSITION":0,"TEXCOORD_0":1},"material":0}]}],
				 "materials":[{"pbrMetallicRoughness":{%s"baseColorFactor":[1,1,1,1]}}],
				 %s
				 "buffers":[{"byteLength":%d}],
				 "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":%d},{"buffer":0,"byteOffset":%d,"byteLength":%d}%s],
				 "accessors":[{"bufferView":0,"componentType":5126,"count":%d,"type":"VEC3"},
				              {"bufferView":1,"componentType":5126,"count":%d,"type":"VEC2"}]}"""
				.formatted(png != null ? "\"baseColorTexture\":{\"index\":0}," : "",
						png != null ? "\"textures\":[{\"source\":0}],\"images\":[{\"bufferView\":2,\"mimeType\":\"image/png\"}]," : "",
						binary.size(), positions.length * 4, positions.length * 4, uvs.length * 4,
						png != null ? ",{\"buffer\":0,\"byteOffset\":" + imageOffset + ",\"byteLength\":" + png.length + "}" : "",
						positions.length / 3, uvs.length / 2);
		byte[] jsonBytes = pad(utf8(json), (byte) ' ');
		byte[] binBytes = binary.toByteArray();
		ByteBuffer out = ByteBuffer.allocate(12 + 8 + jsonBytes.length + 8 + binBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		out.putInt(0x46546C67).putInt(2).putInt(out.capacity());
		out.putInt(jsonBytes.length).putInt(0x4E4F534A).put(jsonBytes);
		out.putInt(binBytes.length).putInt(0x004E4942).put(binBytes);
		return out.array();
	}

	/** A glTF whose only buffer is external; {@code uri} is whatever the test wants the importer to try to open. */
	static byte[] gltfWithExternalBuffer(String uri) {
		return utf8("""
				{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
				 "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
				 "buffers":[{"uri":"%s","byteLength":36}],
				 "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36}],
				 "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"}]}""".formatted(uri));
	}

	/** A glTF with its geometry inlined as a base64 data URI. */
	static byte[] gltfWithDataUri(float[] positions) {
		return gltfWithExternalBuffer("data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(floats(positions)));
	}

	static byte[] floats(float... values) {
		ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (float value : values) buffer.putFloat(value);
		return buffer.array();
	}

	private static byte[] pad(byte[] data, byte filler) {
		byte[] padded = java.util.Arrays.copyOf(data, (data.length + 3) / 4 * 4);
		java.util.Arrays.fill(padded, data.length, padded.length, filler);
		return padded;
	}
}
