package dev.modelmirage.client.gui.kit;

import dev.modelmirage.client.gui.AstolfoUi;

/** Design tokens of the glass UI: translucent white layers over a dark tinted glass window. */
public final class Theme {
	public static final int TEXT = 0xFFF5F5F7;
	public static final int TEXT_SECONDARY = 0xA6EBEBF5;
	public static final int TEXT_TERTIARY = 0x5CEBEBF5;
	public static final int CARD = 0x0FFFFFFF;
	public static final int CARD_STROKE = 0x14FFFFFF;
	public static final int HOVER = 0x0DFFFFFF;
	public static final int PRESSED = 0x1AFFFFFF;
	public static final int SEPARATOR = 0x12FFFFFF;
	public static final int CONTROL = 0x1FFFFFFF;
	public static final int CONTROL_HOVER = 0x2EFFFFFF;
	public static final int TRACK_OFF = 0x33787880;
	public static final int KNOB = 0xFFFFFFFF;
	public static final int DANGER = 0xFFFF453A;
	public static final int SUCCESS = 0xFF30D158;
	public static final int WARNING = 0xFFFFD60A;
	public static final int POPUP_TINT = 0xC8141418;
	public static final int WINDOW_TINT = 0xB40E0E12;

	public static final float TITLE = 13;
	public static final float HEADLINE = 9.5f;
	public static final float BODY = 8.5f;
	public static final float CAPTION = 7;

	public static final float ROW_HEIGHT = 24;
	public static final float RADIUS_CARD = 10;
	public static final float RADIUS_CONTROL = 6;

	private Theme() { }

	public static int accent() { return AstolfoUi.ACCENT; }
}
