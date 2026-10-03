package dev.modelmirage.client.catalog;

import java.io.IOException;
import java.util.Map;
import net.minecraft.resources.Identifier;

/** Decode verified bytes off-thread; install GPU resources on Minecraft's thread. */
public interface ModelLoader {
	Prepared prepare(CatalogModel model, byte[] definition, Map<Identifier, byte[]> textures, Map<Identifier, byte[]> parents) throws IOException;
	interface Prepared {
		void install();
		void discard();
	}
}
