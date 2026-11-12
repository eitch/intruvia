// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import li.intruvia.core.ingest.Fail2banEvent;
import li.intruvia.core.model.*;
import li.intruvia.core.persistence.*;

/** Optional capacity harness. Real packaged application classes, PAT HTTP, local MMDB and PostgreSQL. */
public final class CapacityBrowserFixture {
	public static void main(String[] args) throws Exception {
		int port;
		try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
		String origin = "http://127.0.0.1:" + port;
		try (var database = IngestionIT.database()) {
			Files.writeString(database.runtime.resolve("config/viewer.properties"), "origin=" + origin + "\n");
			var mapping = database.runtime.resolve("config/machine-identities.conf");
			Files.writeString(mapping, "producer=server-a\nsecond=server-b\n");
			Files.setPosixFilePermissions(mapping, PosixFilePermissions.fromString("rw-------"));
			// Existing producer also has read privilege: cookie login exercises the real viewer boundary.
			var config = database.runtime.resolve("config/StrolchConfiguration.xml");
			Files.writeString(config, Files.readString(config)
					// Match Hikari defaults selected by production db.useEnv=true (test credentials stay in isolated XML).
					.replace("<db.pool.connectionTimeout>3000", "<db.pool.connectionTimeout>30000")
					.replace("<db.pool.validationTimeout>2000", "<db.pool.validationTimeout>5000")
					.replace("<db.pool.maximumPoolSize>2</db.pool.maximumPoolSize>",
					"<db.pool.maximumPoolSize>10</db.pool.maximumPoolSize>").replaceAll("<databasePath>[^<]*</databasePath>",
					"<databasePath>" + Path.of(args[0]).toAbsolutePath() + "</databasePath>"));
			try (var app = new IntruviaApplication(database.runtime, port); var client = HttpClient.newHttpClient()) {
				app.start();
				verifyPool(app);
				seed(app);
				var handler = app.agent().getPrivilegeHandler().getPrivilegeHandler();
				List<String> tokens = new ArrayList<>();
				for (String name : List.of("producer", "second")) {
					var owner = handler.authenticate(name, "test-password".toCharArray(), false);
					tokens.add(handler.createPersonalAccessToken(owner, "capacity", ZonedDateTime.now().minusMinutes(1),
							ZonedDateTime.now().plusHours(1), Set.of(), Set.of("event:ingest")));
				}
				System.out.println("CAPACITY_FIXTURE=" + origin);
				System.out.flush();
				try (var trace = System.getenv("CAPACITY_TRACE") == null ? null : traceFailures();
						var input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
						var executor = Executors.newFixedThreadPool(100)) {
					if (!"load".equals(input.readLine())) return;
					int sustained = Integer.parseInt(System.getenv().getOrDefault("CAPACITY_EVENTS", "3000"));
					long start = System.nanoTime();
					List<Future<?>> pending = new ArrayList<>();
					for (int index = 0; index < sustained; index++) {
						long wait = start + index * 100_000_000L - System.nanoTime();
						if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
						final int item = index;
						pending.add(executor.submit(() -> send(client, origin, tokens.get(item % 2), item)));
						if (index % 100 == 0) { System.out.println("PROGRESS=" + index); System.out.flush(); }
					}
					for (var future : pending) future.get(60, TimeUnit.SECONDS);
					pending.clear();
					for (int index = sustained; index < sustained + 100; index++) {
						final int item = index;
						pending.add(executor.submit(() -> send(client, origin, tokens.get(item % 2), item)));
					}
					for (var future : pending) future.get(60, TimeUnit.SECONDS);
					app.agent().getPrivilegeHandler().runAsAgent(ctx -> {
						try (var tx = app.agent().openTx(ctx.getCertificate(), "CapacityCount", true)) {
							System.out.println("COUNTS=" + tx.streamResources(ModelConstants.TYPE_SECURITY_EVENT).count() + "," +
									tx.streamResources(ModelConstants.TYPE_INGEST_RECEIPT).count() + "," + new EventRepository().streamState(tx));
						}
					});
					System.out.println("LOAD_DONE"); System.out.flush();
					input.readLine();
				}
			}
		}
	}

	private static void verifyPool(IntruviaApplication app) throws Exception {
		var handler = app.agent().getContainer().getComponent(li.strolch.persistence.api.PersistenceHandler.class);
		// Test-only introspection of the verified framework's private registry; never print credentials/URLs.
		var registry = handler.getClass().getDeclaredField("dsMap");
		registry.setAccessible(true);
		var pools = (Map<?, ?>) registry.get(handler);
		if (pools.size() != 1) throw new IllegalStateException("Expected one fixture pool");
		var source = (javax.sql.DataSource) pools.values().iterator().next();
		var pool = source.unwrap(com.zaxxer.hikari.HikariDataSource.class);
		if (pool.getMaximumPoolSize() != 10 || pool.getConnectionTimeout() != 30000 || pool.getValidationTimeout() != 5000)
			throw new IllegalStateException("Fixture pool differs from production environment-mode defaults");
		System.out.println("POOL=maximum10,acquisition30000ms,validation5000ms");
	}

	private static jdk.jfr.consumer.RecordingStream traceFailures() {
		var recording = new jdk.jfr.consumer.RecordingStream();
		recording.enable("jdk.JavaExceptionThrow").withStackTrace();
		recording.onEvent("jdk.JavaExceptionThrow", event -> {
			String type = event.getClass("thrownClass").getName();
			if (type.endsWith("StrolchPersistenceException") || type.endsWith("ElementLockingException") ||
					type.endsWith("StrolchTransactionException")) {
				// Diagnostic type/stack only; exception messages can contain credentials/model values.
				System.out.println("FAILURE_TYPE=" + type);
				if (event.getStackTrace() != null)
					event.getStackTrace().getFrames().stream().limit(4).forEach(frame ->
							System.out.println("FAILURE_FRAME=" + frame.getMethod().getType().getName() + "." + frame.getMethod().getName()));
			}
		});
		recording.startAsync();
		return recording;
	}

	private static void seed(IntruviaApplication app) throws Exception {
		for (int batch = 0; batch < 100; batch++) {
			final int first = batch * 1000 + 1;
			app.agent().getPrivilegeHandler().runAsAgent(ctx -> {
				try (var tx = app.agent().openTx(ctx.getCertificate(), "CapacitySeed", false).rollbackOnFailure()) {
					EventStreamLock.acquire(tx);
					var repository = new EventRepository();
					for (int offset = 0; offset < 1000; offset++) {
						var payload = new Fail2banEvent(UUID.randomUUID(), Instant.now(), "8.8.8.8", "seed", null);
						var geo = new SecurityEvent.Geo(GeoStatus.FOUND, null, null, null, null,
								0.0, 0.0, null, null, null, null, null);
						repository.addEvent(tx, payload.toEvent("seed", UUID.randomUUID(), first + offset, Instant.now(), geo));
					}
					repository.updateStreamState(tx, new EventStreamState(Integer.toString(first + 999), "0"));
					tx.commitOnClose();
				}
			});
		}
		System.out.println("SEEDED=100000");
	}

	private static void send(HttpClient client, String origin, String token, int index) {
		String payload = """
				{"schemaVersion":1,"eventId":"%s","occurredAt":"%s","action":"ban","ip":"%s","jail":"capacity-%d"}
				""".formatted(UUID.randomUUID(), Instant.now(), index % 2 == 0 ? "8.8.8.8" : "2606:4700:4700::1111", index);
		try {
			var response = client.send(HttpRequest.newBuilder(URI.create(origin + "/api/v1/fail2ban/events"))
					.timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 201) {
				System.out.println("HTTP_FAILURE=" + response.statusCode() + ",index=" + index); System.out.flush();
			}
		} catch (java.io.IOException e) {
			throw new IllegalStateException("Capacity HTTP failed", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt(); throw new IllegalStateException("Capacity HTTP interrupted", e);
		}
	}
}
