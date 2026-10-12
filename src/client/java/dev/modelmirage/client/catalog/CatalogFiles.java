package dev.modelmirage.client.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Checksum verification and atomic cache writes for catalog downloads. */
final class CatalogFiles {
	private static final Logger LOGGER = LoggerFactory.getLogger("ModelMirage");

	private CatalogFiles() { }

	/** The cached object when it exists and matches the asset's size and checksum, otherwise null. */
	static byte[] readVerified(Path object, CatalogModel.Asset asset) throws IOException {
		if (!Files.exists(object) || Files.size(object) != asset.bytes()) return null;
		byte[] data = Files.readAllBytes(object);
		if (sha256(data).equals(asset.sha256())) return data;
		LOGGER.warn("Discarding corrupt cache object {}", asset.sha256());
		return null;
	}

	static byte[] verified(CatalogModel.Asset asset, byte[] data) throws IOException {
		if (data.length != asset.bytes() || !sha256(data).equals(asset.sha256())) throw new IOException("Asset checksum mismatch: " + asset.path());
		return data;
	}

	/** Cheap presence check without hashing; loading still verifies the checksum. */
	static boolean present(Path object, CatalogModel.Asset asset) {
		try {
			return Files.isRegularFile(object) && Files.size(object) == asset.bytes();
		} catch (IOException unreadable) {
			return false;
		}
	}

	/** Deletes cached objects no catalog entry refers to any more and returns how many bytes were freed. */
	static long prune(Path objects, Set<String> keep) throws IOException {
		if (!Files.isDirectory(objects)) return 0;
		long freed = 0;
		try (Stream<Path> files = Files.list(objects)) {
			for (Path file : files.toList()) {
				if (keep.contains(file.getFileName().toString()) || !Files.isRegularFile(file)) continue;
				freed += Files.size(file);
				Files.delete(file);
			}
		}
		return freed;
	}

	static void write(Path target, byte[] data) throws IOException {
		Files.createDirectories(target.getParent());
		Path temporary = Files.createTempFile(target.getParent(), "download-", ".tmp");
		try {
			Files.write(temporary, data);
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	static String sha256(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}
}
