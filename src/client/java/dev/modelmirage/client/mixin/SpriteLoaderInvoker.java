package dev.modelmirage.client.mixin;

import java.util.List;
import java.util.concurrent.Executor;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SpriteLoader.class)
public interface SpriteLoaderInvoker {
	@Invoker("stitch")
	SpriteLoader.Preparations modelmirage$stitch(List<SpriteContents> sprites, int mipLevels, Executor executor);
}
