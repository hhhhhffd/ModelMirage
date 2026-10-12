package dev.modelmirage.client.gui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import net.minecraft.client.input.KeyEvent;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

class SurfaceTest {
	/** Counts the events it is sent, like a key row waiting for a binding. */
	private static final class Recorder extends Control {
		int keys, drags;

		@Override public void render(Surface surface) { }
		@Override public boolean keyPressed(Surface surface, KeyEvent event) { keys++; return true; }
		@Override public boolean mouseDragged(Surface surface, double mouseX, double mouseY, int button) { drags++; return true; }
	}

	private static final KeyEvent KEY_A = new KeyEvent(GLFW.GLFW_KEY_A, 0, 0);

	@Test
	void resetStopsKeysReachingTheOldFocus() {
		Surface surface = new Surface();
		Recorder row = new Recorder();
		surface.focus(row);
		surface.reset();
		assertFalse(surface.keyPressed(KEY_A));
		assertFalse(surface.isFocused(row));
		assertEquals(0, row.keys);
	}

	@Test
	void resetReleasesTheCapture() {
		Surface surface = new Surface();
		Recorder slider = new Recorder();
		surface.capture(slider);
		surface.reset();
		assertFalse(surface.isCapturing());
		assertFalse(surface.mouseDragged(1, 1, 0));
		assertEquals(0, slider.drags);
	}

	@Test
	void resetClosesThePopup() {
		Surface surface = new Surface();
		Recorder menu = new Recorder();
		surface.open(menu);
		surface.reset();
		assertFalse(surface.hasPopup());
		assertFalse(surface.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0)));
		assertEquals(0, menu.keys);
	}
}
