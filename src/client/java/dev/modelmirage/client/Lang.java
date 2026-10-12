package dev.modelmirage.client;

import net.minecraft.client.resources.language.I18n;

/** Translates UI text for the custom font renderer, which draws plain strings rather than Components. */
public final class Lang {
	private Lang() { }

	public static String tr(String key, Object... args) { return I18n.get(key, args); }
}
