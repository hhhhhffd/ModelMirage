package dev.modelmirage.client.gui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;

/** Per-shape data travels in vertices, allowing differently sized panels in one GUI batch. */
record ShapeRenderState(RenderPipeline pipeline, Matrix3x2f pose, float x, float y, float width,
		float height, float radius, float border, int top, int bottom, ScreenRectangle scissorArea,
		ScreenRectangle bounds, TextureSetup textureSetup) implements GuiElementRenderState {

	@Override
	public void buildVertices(VertexConsumer vertices) {
		vertex(vertices, x, y, -width / 2, -height / 2, top);
		vertex(vertices, x, y + height, -width / 2, height / 2, bottom);
		vertex(vertices, x + width, y + height, width / 2, height / 2, bottom);
		vertex(vertices, x + width, y, width / 2, -height / 2, top);
	}

	private void vertex(VertexConsumer vertices, float x, float y, float u, float v, int color) {
		Vector2f point = pose.transformPosition(x, y, new Vector2f());
		// The custom vertex shader treats Z as metadata, preserving fractional corner radii.
		vertices.addVertex(point.x, point.y, radius + border * 1000f).setUv(u, v).setColor(color);
	}
}
