package dev.modelmirage.client.render;

import static org.lwjgl.assimp.Assimp.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.lwjgl.assimp.AIColor4D;
import org.lwjgl.assimp.AIFace;
import org.lwjgl.assimp.AIFile;
import org.lwjgl.assimp.AIFileCloseProc;
import org.lwjgl.assimp.AIFileFlushProc;
import org.lwjgl.assimp.AIFileIO;
import org.lwjgl.assimp.AIFileOpenProc;
import org.lwjgl.assimp.AIFileReadProc;
import org.lwjgl.assimp.AIFileSeek;
import org.lwjgl.assimp.AIFileTellProc;
import org.lwjgl.assimp.AIFileWriteProc;
import org.lwjgl.assimp.AIMaterial;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIString;
import org.lwjgl.assimp.AITexel;
import org.lwjgl.assimp.AITexture;
import org.lwjgl.assimp.AIVector3D;
import org.lwjgl.system.MemoryUtil;

/**
 * Flattens an interchange 3D file (OBJ, FBX, glTF, Blender, ...) into textured triangles with Assimp.
 * Assimp only sees the verified catalog files through an in-memory file system, never the disk.
 */
final class AssimpScene {
	static final int MAX_TRIANGLES = 100_000;
	private static final int MAX_TEXTURE_SIZE = 2048;
	private static final int FLAGS = aiProcess_Triangulate | aiProcess_PreTransformVertices | aiProcess_SortByPType
			| aiProcess_FlipUVs | aiProcess_ValidateDataStructure;

	/** {@code texture} is the material's base color reference ({@code *N} for embedded) or null. */
	record Material(String texture, int color) { }
	/** Either {@code encoded} PNG/JPEG bytes or raw {@code argb} pixels. */
	record Embedded(String name, byte[] encoded, int width, int height, int[] argb) { }
	/** Per triangle: 9 position floats, 6 uv floats (v grows downwards) and one material index. */
	record Scene(float[] positions, float[] uvs, int[] materialOf, List<Material> materials, List<Embedded> embedded) {
		int triangles() { return materialOf.length; }
	}

	private AssimpScene() { }

	static Scene read(String extension, byte[] model, Map<String, byte[]> sideFiles) throws IOException {
		Map<String, byte[]> files = new HashMap<>();
		sideFiles.forEach((name, data) -> files.put(basename(name), data));
		String main = "model." + extension;
		files.put(main, model);
		try (MemoryFiles io = new MemoryFiles(files)) {
			AIScene scene = aiImportFileEx(main, FLAGS, io.fileIO);
			if (scene == null) throw new IOException("Assimp could not read the model: " + aiGetErrorString());
			try {
				return convert(scene);
			} finally {
				aiReleaseImport(scene);
			}
		} catch (LinkageError unavailable) {
			throw new IOException("Assimp native library is unavailable on this platform", unavailable);
		}
	}

	static String basename(String path) {
		String name = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
		return name.toLowerCase(Locale.ROOT);
	}

	private static Scene convert(AIScene scene) throws IOException {
		int triangles = 0;
		for (int i = 0; i < scene.mNumMeshes(); i++) {
			AIMesh mesh = AIMesh.create(scene.mMeshes().get(i));
			if ((mesh.mPrimitiveTypes() & aiPrimitiveType_TRIANGLE) != 0) triangles += mesh.mNumFaces();
			if (triangles > MAX_TRIANGLES) throw new IOException("Model exceeds " + MAX_TRIANGLES + " triangles");
		}
		float[] positions = new float[triangles * 9];
		float[] uvs = new float[triangles * 6];
		int[] materialOf = new int[triangles];
		int triangle = 0;
		for (int i = 0; i < scene.mNumMeshes(); i++) {
			AIMesh mesh = AIMesh.create(scene.mMeshes().get(i));
			AIVector3D.Buffer vertices = mesh.mVertices();
			AIVector3D.Buffer coordinates = mesh.mTextureCoords(0);
			AIFace.Buffer faces = mesh.mFaces();
			for (int f = 0; f < mesh.mNumFaces() && triangle < triangles; f++) {
				AIFace face = faces.get(f);
				if (face.mNumIndices() != 3) continue;
				for (int corner = 0; corner < 3; corner++) {
					int index = face.mIndices().get(corner);
					AIVector3D vertex = vertices.get(index);
					if (!Float.isFinite(vertex.x()) || !Float.isFinite(vertex.y()) || !Float.isFinite(vertex.z())) {
						throw new IOException("Model contains non-finite vertex positions");
					}
					positions[triangle * 9 + corner * 3] = vertex.x();
					positions[triangle * 9 + corner * 3 + 1] = vertex.y();
					positions[triangle * 9 + corner * 3 + 2] = vertex.z();
					if (coordinates != null) {
						AIVector3D uv = coordinates.get(index);
						uvs[triangle * 6 + corner * 2] = uv.x();
						uvs[triangle * 6 + corner * 2 + 1] = uv.y();
					}
				}
				materialOf[triangle++] = mesh.mMaterialIndex();
			}
		}
		if (triangle == 0) throw new IOException("The model contains no triangles");
		if (triangle < triangles) {
			positions = java.util.Arrays.copyOf(positions, triangle * 9);
			uvs = java.util.Arrays.copyOf(uvs, triangle * 6);
			materialOf = java.util.Arrays.copyOf(materialOf, triangle);
		}

		List<Material> materials = new ArrayList<>();
		for (int i = 0; i < scene.mNumMaterials(); i++) materials.add(material(AIMaterial.create(scene.mMaterials().get(i))));
		List<Embedded> embedded = new ArrayList<>();
		for (int i = 0; i < scene.mNumTextures(); i++) embedded.add(embedded(AITexture.create(scene.mTextures().get(i))));
		return new Scene(positions, uvs, materialOf, List.copyOf(materials), List.copyOf(embedded));
	}

	private static Material material(AIMaterial material) {
		String texture = texture(material, aiTextureType_BASE_COLOR);
		if (texture == null) texture = texture(material, aiTextureType_DIFFUSE);
		Integer color = color(material, AI_MATKEY_BASE_COLOR);
		if (color == null) color = color(material, AI_MATKEY_COLOR_DIFFUSE);
		int argb = color == null ? 0xFFFFFFFF : color;
		float[] opacity = new float[1];
		if (aiGetMaterialFloatArray(material, AI_MATKEY_OPACITY, aiTextureType_NONE, 0, opacity, new int[] {1}) == aiReturn_SUCCESS) {
			argb = argb & 0xFFFFFF | Math.round(Math.clamp(opacity[0], 0f, 1f) * ((argb >>> 24) / 255f) * 255) << 24;
		}
		return new Material(texture, argb);
	}

	private static String texture(AIMaterial material, int type) {
		if (aiGetMaterialTextureCount(material, type) == 0) return null;
		try (AIString path = AIString.calloc()) {
			int result = aiGetMaterialTexture(material, type, 0, path, (int[]) null, null, null, null, null, null);
			return result == aiReturn_SUCCESS && !path.dataString().isBlank() ? path.dataString() : null;
		}
	}

	private static Integer color(AIMaterial material, String key) {
		try (AIColor4D color = AIColor4D.calloc()) {
			if (aiGetMaterialColor(material, key, aiTextureType_NONE, 0, color) != aiReturn_SUCCESS) return null;
			return channel(color.a()) << 24 | channel(color.r()) << 16 | channel(color.g()) << 8 | channel(color.b());
		}
	}

	private static int channel(float value) {
		return Math.round(Math.clamp(value, 0f, 1f) * 255);
	}

	private static Embedded embedded(AITexture texture) throws IOException {
		String name = texture.mFilename().dataString();
		if (texture.mHeight() == 0) {
			int bytes = texture.mWidth();
			if (bytes <= 0 || bytes > 16 * 1024 * 1024) throw new IOException("Embedded texture exceeds size limit");
			byte[] encoded = new byte[bytes];
			texture.pcDataCompressed().get(encoded);
			return new Embedded(name, encoded, 0, 0, null);
		}
		int width = texture.mWidth(), height = texture.mHeight();
		if (width > MAX_TEXTURE_SIZE || height > MAX_TEXTURE_SIZE) throw new IOException("Embedded texture exceeds size limit");
		AITexel.Buffer texels = texture.pcData();
		int[] argb = new int[width * height];
		for (int i = 0; i < argb.length; i++) {
			AITexel texel = texels.get(i);
			argb[i] = (texel.a() & 0xFF) << 24 | (texel.r() & 0xFF) << 16 | (texel.g() & 0xFF) << 8 | texel.b() & 0xFF;
		}
		return new Embedded(name, null, width, height, argb);
	}

	/** Read-only Assimp file system over the files of one catalog entry, matched by lowercase basename. */
	private static final class MemoryFiles implements AutoCloseable {
		private final Map<String, byte[]> files;
		private final Map<Long, OpenFile> open = new HashMap<>();
		private final AIFileReadProc read = AIFileReadProc.create(this::read);
		private final AIFileWriteProc write = AIFileWriteProc.create((file, buffer, size, count) -> 0);
		private final AIFileTellProc tell = AIFileTellProc.create(file -> open.get(file).position);
		private final AIFileTellProc size = AIFileTellProc.create(file -> open.get(file).data.limit());
		private final AIFileSeek seek = AIFileSeek.create(this::seek);
		private final AIFileFlushProc flush = AIFileFlushProc.create(file -> { });
		private final AIFileOpenProc openProc = AIFileOpenProc.create(this::open);
		private final AIFileCloseProc closeProc = AIFileCloseProc.create(this::close);
		final AIFileIO fileIO = AIFileIO.calloc().OpenProc(openProc).CloseProc(closeProc);

		private MemoryFiles(Map<String, byte[]> files) { this.files = files; }

		private long open(long fileIO, long fileName, long openMode) {
			if (MemoryUtil.memUTF8(openMode).contains("w")) return MemoryUtil.NULL;
			byte[] bytes = files.get(basename(MemoryUtil.memUTF8(fileName)));
			if (bytes == null) return MemoryUtil.NULL;
			ByteBuffer data = MemoryUtil.memAlloc(Math.max(1, bytes.length)).put(bytes).flip();
			AIFile file = AIFile.calloc().ReadProc(read).WriteProc(write).TellProc(tell).FileSizeProc(size).SeekProc(seek).FlushProc(flush);
			open.put(file.address(), new OpenFile(data));
			return file.address();
		}

		private void close(long fileIO, long file) {
			OpenFile closed = open.remove(file);
			if (closed == null) return;
			MemoryUtil.memFree(closed.data);
			AIFile.create(file).free();
		}

		private long read(long file, long buffer, long size, long count) {
			OpenFile source = open.get(file);
			if (source == null || size <= 0) return 0;
			long items = Math.min(count, (source.data.limit() - source.position) / size);
			MemoryUtil.memCopy(MemoryUtil.memAddress(source.data) + source.position, buffer, items * size);
			source.position += items * size;
			return items;
		}

		private int seek(long file, long offset, int origin) {
			OpenFile source = open.get(file);
			if (source == null) return aiReturn_FAILURE;
			long target = switch (origin) {
				case aiOrigin_SET -> offset;
				case aiOrigin_CUR -> source.position + offset;
				case aiOrigin_END -> source.data.limit() + offset;
				default -> -1;
			};
			if (target < 0 || target > source.data.limit()) return aiReturn_FAILURE;
			source.position = target;
			return aiReturn_SUCCESS;
		}

		@Override
		public void close() {
			for (long file : List.copyOf(open.keySet())) close(fileIO.address(), file);
			fileIO.free();
			for (var callback : List.of(read, write, tell, size, seek, flush, openProc, closeProc)) callback.free();
		}

		private static final class OpenFile {
			private final ByteBuffer data;
			private long position;
			private OpenFile(ByteBuffer data) { this.data = data; }
		}
	}
}
