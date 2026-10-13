package dev.modelmirage.client.visual;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Pdh;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinNT.HANDLEByReference;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;

/**
 * Machine and connection numbers for the watermark. Slow probes (CPU load and clock) run on a daemon thread
 * every second and only while the watermark shows them, so the render thread only reads volatile fields.
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
	private static long serverGameTime = -1, serverNanos;
	private static volatile long lastTimeUpdate;
	/** Real milliseconds per server tick between the previous two time updates, or -1 without a usable pair. */
	private static double lastTickMs = -1;
	/** Time updates further apart than this are an outage or a pause, not jitter, and restart the measurement. */
	private static final long MAX_UPDATE_GAP = 2_500_000_000L;
	private static volatile float jitterMs = -1;
	private static long lastSample;
	private static double shownGpu = -1;
	private static volatile float serverTps = -1;
	private static volatile ScheduledExecutorService probes;
	private static CentralProcessor processor;
	private static long[] cpuTicks;
	/** Windows performance counter of the actual clock as a share of the base clock; null when not opened or failed. */
	private static HANDLE clockQuery;
	private static HANDLE clockCounter;
	private static long clockValue, clockBase;
	private static boolean probeWarned, clockWarned;

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
	/**
	 * Sampled once a second; the raw value changes every frame and the digits would never settle. Minecraft divides
	 * GPU time by frame time, which passes 100 when the GPU works on several frames at once, so it is capped there.
	 */
	static double gpuLoad() {
		long now = System.nanoTime();
		if (now - lastSample > 1_000_000_000L) {
			lastSample = now;
			double load = Minecraft.getInstance().getGpuUtilization();
			shownGpu = load < 0 ? load : Math.min(100, load);
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

	/**
	 * The server sends its game time about once a second; ticks gained per real second is its TPS. Called on the
	 * network thread when the packet arrives, so how long the client takes to handle it does not count as jitter.
	 */
	public static void timeUpdate(long gameTime, long now) {
		long ticks = gameTime - serverGameTime, elapsed = now - serverNanos;
		if (serverGameTime >= 0 && ticks > 0 && elapsed > 0 && elapsed <= MAX_UPDATE_GAP) {
			var level = Minecraft.getInstance().level;
			float target = level == null ? 20 : level.tickRateManager().tickrate();
			float sample = Math.min((float) (ticks * 1e9 / elapsed), target);
			serverTps = serverTps < 0 ? sample : serverTps * 0.6f + sample * 0.4f;
			// Jitter is how much the spacing of updates changes from one to the next, per tick so a steadily slow
			// server does not count; like RFC 3550 it is smoothed over the last updates.
			double tickMs = elapsed / 1e6 / ticks;
			if (lastTickMs >= 0) {
				float change = (float) (Math.abs(tickMs - lastTickMs) * ticks);
				jitterMs = jitterMs < 0 ? change : jitterMs * 0.8f + change * 0.2f;
			}
			lastTickMs = tickMs;
		} else lastTickMs = -1;
		lastTimeUpdate = now;
		serverGameTime = gameTime;
		serverNanos = now;
	}

	/** Forget the remote server's history after a disconnect or world change. */
	public static void resetServer() {
		serverGameTime = -1;
		serverTps = -1;
		jitterMs = -1;
		lastTickMs = -1;
		lastTimeUpdate = 0;
	}

	/** Smoothed jitter of the server's time updates in milliseconds; -1 before it is known and in singleplayer, where there is no network. */
	static float jitter() { return Minecraft.getInstance().hasSingleplayerServer() ? -1 : jitterMs; }

	/** Seconds without any time update from the server (sent every second), or 0 while the link is fine. */
	static float silence() {
		Minecraft client = Minecraft.getInstance();
		if (lastTimeUpdate == 0 || client.isPaused()) return 0;
		float seconds = (System.nanoTime() - lastTimeUpdate) / 1e9f;
		return seconds > 2.5f ? seconds : 0;
	}

	/** "localhost" for singleplayer and an open LAN world, else the address typed in the server list. */
	static String server() {
		Minecraft client = Minecraft.getInstance();
		if (client.hasSingleplayerServer() || client.getCurrentServer() == null) return "localhost";
		return client.getCurrentServer().ip;
	}

	static long usedMemory() { Runtime runtime = Runtime.getRuntime(); return runtime.totalMemory() - runtime.freeMemory(); }
	static long maxMemory() { return Runtime.getRuntime().maxMemory(); }

	private static void ensureProbes() {
		// Checked without the lock first: this runs every frame while CPU values are shown.
		if (probes != null) return;
		synchronized (SystemStats.class) {
			if (probes != null) return;
			var executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("ModelMirage-stats").factory());
			executor.scheduleWithFixedDelay(SystemStats::probe, 0, 1, TimeUnit.SECONDS);
			probes = executor;
		}
	}

	private static void probe() {
		if (!probesWanted) return;
		probesWanted = false;
		try {
			if (processor == null) {
				processor = new SystemInfo().getHardware().getProcessor();
				cpuTicks = processor.getSystemCpuLoadTicks();
				if (Platform.isWindows()) openClockCounter();
				return;
			}
			// The JMX system load reports -1 on some Windows machines; OSHI's tick counters work everywhere.
			cpuLoad = processor.getSystemCpuLoadBetweenTicks(cpuTicks) * 100;
			cpuTicks = processor.getSystemCpuLoadTicks();
			long hz = clockQuery != null ? windowsClock() : averageClock();
			cpuGhz = hz > 0 ? hz / 1e9 : -1;
		} catch (RuntimeException | LinkageError failure) {
			// Unsupported platforms keep the last value and the chip shows a dash; say why once.
			if (!probeWarned) LoggerFactory.getLogger("ModelMirage").warn("CPU probe unavailable: {}", failure.toString());
			probeWarned = true;
		}
	}

	/** Linux reports the live clock per core; elsewhere OSHI may only know the base clock. */
	private static long averageClock() {
		long sum = 0, count = 0;
		for (long hz : processor.getCurrentFreq()) if (hz > 0) { sum += hz; count++; }
		return count > 0 ? sum / count : processor.getMaxFreq();
	}

	/**
	 * Windows only reports the base clock directly. "% Processor Performance" is the actual clock relative to it
	 * (above 100 while boosting); the English counter name works on localised systems too.
	 */
	private static void openClockCounter() {
		HANDLEByReference query = new HANDLEByReference(), counter = new HANDLEByReference();
		int status = Pdh.INSTANCE.PdhOpenQuery(null, null, query);
		if (status == 0) {
			status = Pdh.INSTANCE.PdhAddEnglishCounter(query.getValue(), "\\Processor Information(_Total)\\% Processor Performance", new BaseTSD.DWORD_PTR(0), counter);
			if (status != 0) Pdh.INSTANCE.PdhCloseQuery(query.getValue());
		}
		if (status != 0) { warnClock(status); return; }
		clockQuery = query.getValue();
		clockCounter = counter.getValue();
		windowsClock();
	}

	/** The counter's raw value and base both grow; their ratio between two samples is the average performance share. */
	private static long windowsClock() {
		Pdh.PDH_RAW_COUNTER raw = new Pdh.PDH_RAW_COUNTER();
		int status = Pdh.INSTANCE.PdhCollectQueryData(clockQuery);
		if (status == 0) status = Pdh.INSTANCE.PdhGetRawCounterValue(clockCounter, null, raw);
		// PDH_CSTATUS_VALID_DATA (0) and PDH_CSTATUS_NEW_DATA (1) are both usable samples.
		if (status == 0 && raw.CStatus > 1) status = raw.CStatus;
		if (status != 0) {
			warnClock(status);
			closeClockCounter();
			return averageClock();
		}
		long value = raw.FirstValue - clockValue, base = raw.SecondValue - clockBase;
		boolean first = clockBase == 0;
		clockValue = raw.FirstValue;
		clockBase = raw.SecondValue;
		if (first || base <= 0 || value < 0) return averageClock();
		return Math.round(processor.getMaxFreq() * (value / (double) base) / 100);
	}

	private static void warnClock(int status) {
		if (!clockWarned) LoggerFactory.getLogger("ModelMirage").warn("CPU clock counter unavailable (PDH status 0x{}); showing the base clock", Integer.toHexString(status));
		clockWarned = true;
	}

	private static void closeClockCounter() {
		if (clockQuery != null) Pdh.INSTANCE.PdhCloseQuery(clockQuery);
		clockQuery = null;
		clockCounter = null;
	}

	/** The game is exiting; Windows releases the clock counter with the process. */
	public static void close() {
		if (probes != null) probes.shutdownNow();
	}
}
