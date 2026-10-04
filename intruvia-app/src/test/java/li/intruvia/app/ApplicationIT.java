// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import li.intruvia.core.geo.GeoIpComponent;
import li.intruvia.core.model.GeoStatus;
import li.strolch.agent.api.ComponentState;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class ApplicationIT {
	private DatabaseFixture database;

	@Before
	public void createDatabase() throws Exception {
		this.database = new DatabaseFixture();
	}

	@After
	public void dropDatabase() throws Exception {
		if (this.database != null)
			this.database.close();
	}

	@Test(timeout = 60000)
	public void assembledApplicationServesPageAndHealthThenTerminates() throws Exception {
		Path distribution = Path.of("target/intruvia").toAbsolutePath();
		Path log = Path.of("target/application-smoke.log").toAbsolutePath();
		ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
				"-jar", distribution.resolve("intruvia-app-0.0.1.jar").toString(), this.database.runtime.toString(), "0")
				.directory(distribution.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
		builder.environment().putAll(this.database.productionEnvironment());
		Process process = builder.start();
		try {
			int port = awaitPort(process, log);
			try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
				HttpResponse<String> page = get(client, port, "/");
				assertEquals(200, page.statusCode());
				assertTrue(page.body().contains("<h1>Intruvia</h1>"));
				assertTrue(page.headers().firstValue("Content-Type").orElseThrow().startsWith("text/html"));
				assertEquals(page.body(), get(client, port, "/index.html").body());
				HttpResponse<String> health = get(client, port, "/health/live");
				assertEquals(200, health.statusCode());
				assertEquals("{\"status\":\"UP\"}", health.body());
				assertTrue(health.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
				for (String path : new String[]{"/config/StrolchConfiguration.xml", "/api/v1/events", "/health/ready", "/missing"})
					assertEquals(path, 404, get(client, port, path).statusCode());
			}
		} finally {
			process.destroy();
			if (!process.waitFor(15, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				fail("Process did not shut down within 15 seconds: " + Files.readString(log));
			}
		}
		String output = Files.readString(log);
		assertTrue(output, output.contains("Intruvia stopped: Jetty STOPPED; Strolch DESTROYED"));
		assertFalse(output, output.contains("Application shutdown failed"));
	}

	@Test(timeout = 30000)
	public void closesBothLifecyclesAndAllowsRepeatedClose() throws Exception {
		IntruviaApplication application = new IntruviaApplication(this.database.runtime, 0);
		GeoIpComponent geo;
		try (application) {
			application.start();
			assertTrue(application.port() > 0);
			geo = application.agent().getContainer().getComponent(GeoIpComponent.class);
			assertEquals(ComponentState.STARTED, geo.getState());
			assertEquals(GeoStatus.UNAVAILABLE, geo.enrich("8.8.8.8").status());
			assertEquals(GeoStatus.NON_PUBLIC, geo.enrich("2001:db8::1").status());
		}
		application.close();
		assertEquals(ComponentState.DESTROYED, geo.getState());
		assertTrue("Closed connector has no listening port", application.port() < 0);
	}

	private static int awaitPort(Process process, Path log) throws Exception {
		var pattern = Pattern.compile("Intruvia started: http://127\\.0\\.0\\.1:(\\d+)");
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (System.nanoTime() < deadline) {
			String output = Files.readString(log);
			var matcher = pattern.matcher(output);
			if (matcher.find())
				return Integer.parseInt(matcher.group(1));
			assertTrue(output, process.isAlive());
			Thread.sleep(100);
		}
		throw new AssertionError("Startup timed out: " + Files.readString(log));
	}

	private static HttpResponse<String> get(HttpClient client, int port, String path) throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
				.timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
	}
}
