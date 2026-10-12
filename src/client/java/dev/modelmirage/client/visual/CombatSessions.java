package dev.modelmirage.client.visual;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.LoggerFactory;

/**
 * K/D/A counts per server address (per world in singleplayer), kept on disk. Reconnecting after a kick or a crash,
 * or switching modes inside one network, continues the same session; a server not played for 15 minutes starts
 * from zero on the next join. Each counter can also be reset by hand.
 */
public final class CombatSessions {
	static final long PAUSE_MILLIS = 15 * 60_000L;
	private static final long TOUCH_MILLIS = 60_000L;
	static Path file = FabricLoader.getInstance().getConfigDir().resolve("modelmirage/combat.json");
	private static Map<String, Session> sessions;
	private static Session active;
	private static long lastTouch;

	/** The three counters; each has its own deaths so resetting one leaves the others intact. */
	public enum Counter { PVP, TOTEMS, PVE }

	static final class Session {
		int playerKills, pvpDeaths, pvpAssists, totemsPopped, totemsLost, totemAssists, mobKills, pveDeaths, pveAssists;
		long lastSeen;
	}

	private CombatSessions() { }

	/** Address of the connected server, or the world name in singleplayer. */
	public static String key(Minecraft client) {
		var local = client.getSingleplayerServer();
		if (local != null) return "singleplayer/" + local.getWorldData().getLevelName();
		var server = client.getCurrentServer();
		return server == null ? "unknown" : server.ip.toLowerCase(Locale.ROOT);
	}

	public static void enter(String key, long now) {
		load();
		Session session = sessions.get(key);
		if (session == null || now - session.lastSeen > PAUSE_MILLIS) {
			session = new Session();
			sessions.put(key, session);
		}
		active = session;
		touch(now);
	}

	public static void leave(long now) {
		if (active != null) touch(now);
		active = null;
	}

	/** Keeps "last seen" fresh while playing, so a crash still counts as a short break. */
	public static void tick(long now) {
		if (active != null && now - lastTouch >= TOUCH_MILLIS) touch(now);
	}

	public static void reset(Counter counter) {
		if (active == null) return;
		switch (counter) {
			case PVP -> active.playerKills = active.pvpDeaths = active.pvpAssists = 0;
			case TOTEMS -> active.totemsPopped = active.totemsLost = active.totemAssists = 0;
			case PVE -> active.mobKills = active.pveDeaths = active.pveAssists = 0;
		}
		touch(System.currentTimeMillis());
	}

	static void record(Consumer<Session> change, long now) {
		if (active == null) return;
		change.accept(active);
		touch(now);
	}

	static String format(Counter counter) {
		if (active == null) return "0/0/0";
		return switch (counter) {
			case PVP -> active.playerKills + "/" + active.pvpDeaths + "/" + active.pvpAssists;
			case TOTEMS -> active.totemsPopped + "/" + active.totemsLost + "/" + active.totemAssists;
			case PVE -> active.mobKills + "/" + active.pveDeaths + "/" + active.pveAssists;
		};
	}

	/** Drops the in-memory state, as after a restart; tests use it to check what was saved. */
	static void forget() {
		sessions = null;
		active = null;
		lastTouch = 0;
	}

	private static void touch(long now) {
		active.lastSeen = now;
		lastTouch = now;
		save(now);
	}

	private static void load() {
		if (sessions != null) return;
		sessions = new HashMap<>();
		if (!Files.exists(file)) return;
		try {
			for (var entry : JsonParser.parseString(Files.readString(file)).getAsJsonObject().entrySet()) {
				JsonObject saved = entry.getValue().getAsJsonObject();
				Session session = new Session();
				session.playerKills = saved.get("playerKills").getAsInt();
				session.pvpDeaths = saved.get("pvpDeaths").getAsInt();
				session.totemsPopped = saved.get("totemsPopped").getAsInt();
				session.totemsLost = saved.get("totemsLost").getAsInt();
				session.mobKills = saved.get("mobKills").getAsInt();
				session.pveDeaths = saved.get("pveDeaths").getAsInt();
				// Assists were added after the first sessions were saved.
				session.pvpAssists = saved.has("pvpAssists") ? saved.get("pvpAssists").getAsInt() : 0;
				session.totemAssists = saved.has("totemAssists") ? saved.get("totemAssists").getAsInt() : 0;
				session.pveAssists = saved.has("pveAssists") ? saved.get("pveAssists").getAsInt() : 0;
				session.lastSeen = saved.get("lastSeen").getAsLong();
				sessions.put(entry.getKey(), session);
			}
		} catch (IOException | RuntimeException failure) {
			// A damaged file costs the old counts, not the game: start fresh and say why.
			sessions.clear();
			LoggerFactory.getLogger("ModelMirage").error("Could not read K/D sessions from {}", file, failure);
		}
	}

	/** Writes every session that could still continue; expired ones would start from zero anyway. */
	private static void save(long now) {
		sessions.values().removeIf(session -> session != active && now - session.lastSeen > PAUSE_MILLIS);
		JsonObject root = new JsonObject();
		sessions.forEach((key, session) -> {
			JsonObject saved = new JsonObject();
			saved.addProperty("playerKills", session.playerKills);
			saved.addProperty("pvpDeaths", session.pvpDeaths);
			saved.addProperty("totemsPopped", session.totemsPopped);
			saved.addProperty("totemsLost", session.totemsLost);
			saved.addProperty("mobKills", session.mobKills);
			saved.addProperty("pveDeaths", session.pveDeaths);
			saved.addProperty("pvpAssists", session.pvpAssists);
			saved.addProperty("totemAssists", session.totemAssists);
			saved.addProperty("pveAssists", session.pveAssists);
			saved.addProperty("lastSeen", session.lastSeen);
			root.add(key, saved);
		});
		try {
			Files.createDirectories(file.getParent());
			Path temporary = Files.createTempFile(file.getParent(), "combat-", ".tmp");
			try {
				Files.writeString(temporary, root.toString());
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
			} finally {
				Files.deleteIfExists(temporary);
			}
		} catch (IOException failure) {
			LoggerFactory.getLogger("ModelMirage").error("Could not save K/D sessions to {}", file, failure);
		}
	}
}
