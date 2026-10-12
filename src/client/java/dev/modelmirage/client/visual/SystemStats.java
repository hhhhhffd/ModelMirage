package dev.modelmirage.client.visual;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.stats.Stats;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;

/**
 * Machine and connection numbers for the watermark. Slow probes (CPU load and clock) run on a daemon thread
 * every two seconds and only while the watermark shows them, so the render thread only reads volatile fields.
 */
public final class SystemStats {
	private static final int FRAMES = 1000;
	private static final long[] FRAME_NANOS = new long[FRAMES];
	private static int frameCount, frameCursor;
	private static long lastLowUpdate;
	private static int lowFps;
	private static volatile boolean probesWanted;
	private static volatile double cpuLoad = -1;
	private static volatile double cpuGhz = -1;
	private static volatile boolean gpuWanted;
	private static long serverGameTime = -1, serverNanos, lastTimeUpdate;
	private static volatile float jitterMs = -1;
	private static long lastStatsRequest;
	private static final long STATS_INTERVAL = 30_000_000_000L;
	private static long lastSample;
	private static double shownGpu = -1;
	private static volatile float serverTps = -1;
	private static ScheduledExecutorService probes;
	private static CentralProcessor processor;
	private static boolean probeWarned;

	private SystemStats() { }

	/** Called once per HUD frame with the previous frame's duration. */
	static void frame(long nanos) {
		if (nanos <= 0) return;
		FRAME_NANOS[frameCursor] = nanos;
		frameCursor = (frameCursor + 1) % FRAMES;
		frameCount = Math.min(FRAMES, frameCount + 1);
	}

	/** FPS of the slowest 1% of the last thousand frames, refreshed twice a second. */
	static int lowFps() {
		long now = System.nanoTime();
		if (now - lastLowUpdate > 500_000_000L && frameCount > 0) {
			lastLowUpdate = now;
			long[] sorted = Arrays.copyOf(FRAME_NANOS, frameCount);
			Arrays.sort(sorted);
			long slow = sorted[Math.max(0, (int) Math.floor(frameCount * 0.99) - 1)];
			lowFps = (int) Math.round(1e9 / slow);
		}
		return lowFps;
	}

	/** Whole-system CPU load in percent, or -1 until the first sample. */
	static double cpuLoad() { probesWanted = true; ensureProbes(); return cpuLoad; }
	/** Average current core clock in GHz, or -1 when the platform does not report it. */
	static double cpuGhz() { probesWanted = true; ensureProbes(); return cpuGhz; }

	/** Minecraft only times the GPU while F3 asks for it; the watermark asks too while it shows the value. */
	public static boolean gpuWanted() { return gpuWanted; }
	static void wantGpu(boolean wanted) { gpuWanted = wanted; }
	/** Sampled once a second; the raw value changes every frame and the digits would never settle. */
	static double gpuLoad() {
		long now = System.nanoTime();
		if (now - lastSample > 1_000_000_000L) {
			lastSample = now;
			shownGpu = Minecraft.getInstance().getGpuUtilization();
		}
		return shownGpu;
	}

	/** Ticks per second: measured on the integrated server, estimated from time updates on a remote one. */
	static float tps() {
		Minecraft client = Minecraft.getInstance();
		var server = client.getSingleplayerServer();
		if (server != null) {
			float target = server.tickRateManager().tickrate();
			double mspt = server.getAverageTickTimeNanos() / 1e6;
			return (float) Math.min(target, mspt <= 0 ? target : 1000 / mspt);
		}
		return serverTps;
	}

	/** The server sends its game time about once a second; ticks gained per real second is its TPS. */
	public static void timeUpdate(long gameTime) {
		long now = System.nanoTime();
		if (serverGameTime >= 0 && now > serverNanos && gameTime >= serverGameTime) {
			float target = Minecraft.getInstance().level == null ? 20 : Minecraft.getInstance().level.tickRateManager().tickrate();
			float sample = Math.min((float) ((gameTime - serverGameTime) * 1e9 / (now - serverNanos)), target);
			serverTps = serverTps < 0 ? sample : serverTps * 0.6f + sample * 0.4f;
			// Updates leave the server at fixed tick steps; how far their arrival drifts from that is the connection's jitter.
			float drift = (float) Math.abs((now - serverNanos) / 1e6 - (gameTime - serverGameTime) * 1000.0 / target);
			jitterMs = jitterMs < 0 ? drift : jitterMs * 0.75f + drift * 0.25f;
		}
		lastTimeUpdate = now;
		serverGameTime = gameTime;
		serverNanos = now;
	}

	/** Forget the remote server's history after a disconnect or world change. */
	public static void resetServer() {
		serverGameTime = -1;
		serverTps = -1;
		jitterMs = -1;
		lastTimeUpdate = 0;
		lastStatsRequest = 0;
	}

	/** Average drift of the server's time updates in milliseconds, or -1 before two have arrived. */
	static float jitter() { return jitterMs; }

	/** Seconds without any time update from the server (sent every second), or 0 while the link is fine. */
	static float silence() {
		Minecraft client = Minecraft.getInstance();
		if (lastTimeUpdate == 0 || client.isPaused()) return 0;
		float seconds = (System.nanoTime() - lastTimeUpdate) / 1e9f;
		return seconds > 2.5f ? seconds : 0;
	}

	/**
	 * Kills per death from the player's statistics. The server only sends them on request (the vanilla
	 * statistics screen asks the same way), so they are refreshed every 30 seconds while shown.
	 */
	static String killDeathRatio() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.getConnection() == null) return "-";
		long now = System.nanoTime();
		if (lastStatsRequest == 0 || now - lastStatsRequest > STATS_INTERVAL) {
			lastStatsRequest = now;
			client.getConnection().send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
		}
		int kills = client.player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAYER_KILLS));
		int deaths = client.player.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS));
		return String.format(java.util.Locale.ROOT, "%.2f", kills / (double) Math.max(1, deaths));
	}

	/** "localhost" for singleplayer and an open LAN world, else the address typed in the server list. */
	static String server() {
		Minecraft client = Minecraft.getInstance();
		if (client.hasSingleplayerServer() || client.getCurrentServer() == null) return "localhost";
		return client.getCurrentServer().ip;
	}

	static long usedMemory() { Runtime runtime = Runtime.getRuntime(); return runtime.totalMemory() - runtime.freeMemory(); }
	static long maxMemory() { return Runtime.getRuntime().maxMemory(); }

	private static synchronized void ensureProbes() {
		if (probes != null) return;
		probes = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("ModelMirage-stats").factory());
		probes.scheduleWithFixedDelay(SystemStats::probe, 0, 2, TimeUnit.SECONDS);
	}

	private static void probe() {
		if (!probesWanted) return;
		probesWanted = false;
		try {
			if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
				double load = os.getCpuLoad();
				if (load >= 0) cpuLoad = load * 100;
			}
			if (processor == null) processor = new SystemInfo().getHardware().getProcessor();
			long[] current = processor.getCurrentFreq();
			long sum = 0, count = 0;
			for (long hz : current) if (hz > 0) { sum += hz; count++; }
			long hz = count > 0 ? sum / count : processor.getMaxFreq();
			cpuGhz = hz > 0 ? hz / 1e9 : -1;
		} catch (RuntimeException | LinkageError failure) {
			// Unsupported platforms keep the last value and the chip shows a dash; say why once.
			if (!probeWarned) LoggerFactory.getLogger("ModelMirage").warn("CPU probe unavailable: {}", failure.toString());
			probeWarned = true;
		}
	}

	public static void close() {
		if (probes != null) probes.shutdownNow();
	}
}
