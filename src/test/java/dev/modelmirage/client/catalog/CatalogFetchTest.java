package dev.modelmirage.client.catalog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CatalogFetchTest {
	private static final Duration DEADLINE = Duration.ofSeconds(5);
	private final CountDownLatch release = new CountDownLatch(1);
	private HttpServer server;
	private HttpClient http;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/ok", exchange -> {
			exchange.getResponseHeaders().add("ETag", "\"v1\"");
			if ("\"v1\"".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
				exchange.sendResponseHeaders(304, -1);
			} else {
				exchange.sendResponseHeaders(200, 5);
				try (OutputStream body = exchange.getResponseBody()) { body.write(new byte[] {1, 2, 3, 4, 5}); }
			}
			exchange.close();
		});
		server.createContext("/large", exchange -> {
			// Chunked, so the size is only known while reading.
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream body = exchange.getResponseBody()) { body.write(new byte[64 * 1024]); }
			exchange.close();
		});
		server.createContext("/stall", exchange -> {
			// Headers and the first bytes arrive, then the server goes silent.
			exchange.sendResponseHeaders(200, 0);
			OutputStream body = exchange.getResponseBody();
			body.write(new byte[] {1, 2, 3});
			body.flush();
			try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
			exchange.close();
		});
		server.createContext("/missing", exchange -> { exchange.sendResponseHeaders(404, -1); exchange.close(); });
		server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
		server.start();
		http = HttpClient.newHttpClient();
	}

	@AfterEach
	void stop() {
		release.countDown();
		http.close();
		server.stop(0);
	}

	private URI url(String path) { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path); }

	@Test
	void returnsTheBodyAndItsTag() throws Exception {
		var fetched = CatalogService.fetch(http, url("/ok"), 5, null, DEADLINE);
		assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, fetched.data());
		assertEquals("\"v1\"", fetched.tag());
	}

	@Test
	void notModifiedKeepsTheTagWithoutABody() throws Exception {
		var fetched = CatalogService.fetch(http, url("/ok"), 5, "\"v1\"", DEADLINE);
		assertNull(fetched.data());
		assertEquals("\"v1\"", fetched.tag());
	}

	@Test
	void rejectsABodyLargerThanTheLimit() {
		IOException failure = assertThrows(IOException.class, () -> CatalogService.fetch(http, url("/ok"), 4, null, DEADLINE));
		assertTrue(failure.getMessage().startsWith("Download exceeds size limit"), failure.getMessage());
		failure = assertThrows(IOException.class, () -> CatalogService.fetch(http, url("/large"), 1024, null, DEADLINE));
		assertTrue(failure.getMessage().startsWith("Download exceeds size limit"), failure.getMessage());
	}

	@Test
	void givesUpOnABodyThatStopsArriving() {
		long start = System.nanoTime();
		IOException failure = assertThrows(IOException.class,
				() -> CatalogService.fetch(http, url("/stall"), 1024, null, Duration.ofMillis(500)));
		long millis = (System.nanoTime() - start) / 1_000_000;
		assertTrue(failure.getMessage().startsWith("Download timed out"), failure.getMessage());
		assertTrue(millis < 3000, "took " + millis + " ms");
	}

	@Test
	void reportsHttpErrors() {
		IOException failure = assertThrows(IOException.class, () -> CatalogService.fetch(http, url("/missing"), 1024, null, DEADLINE));
		assertEquals("HTTP 404: " + url("/missing"), failure.getMessage());
	}

	@Test
	void refusesPlainHttpToOtherHosts() {
		IOException failure = assertThrows(IOException.class,
				() -> CatalogService.fetch(http, URI.create("http://example.com/index.json"), 1024, null, DEADLINE));
		assertEquals("Catalog requires HTTPS", failure.getMessage());
	}
}
