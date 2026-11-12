// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import com.google.gson.JsonParser;
import li.intruvia.core.geo.GeoIpComponent;
import li.intruvia.rest.stream.EventStreamComponent;
import li.strolch.agent.api.ComponentState;
import org.junit.Test;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class RuntimeOperationsIT {
	private static final String ORIGIN = "https://intruvia.example.org";

	@Test(timeout = 60000)
	public void diagnosticsRequireOperatorAndDbOutageFailsReadinessWithoutLeaking() throws Exception {
		try (var db = ViewerSessionIT.database(ORIGIN); var client = HttpClient.newHttpClient()) {
			var roles = db.runtime.resolve("config/PrivilegeRoles.xml");
			Files.writeString(roles, Files.readString(roles).replace("<Role name=\"issuer\">", """
					<Role name="issuer"><Privilege name="runtime:diagnostics" policy="DefaultPrivilege"><AllAllowed>true</AllAllowed></Privilege>
					"""));
			try (var app = new IntruviaApplication(db.runtime, 0)) {
				app.start();
				assertEquals(200, get(client, app, "/health/live", null).statusCode());
				assertEquals(200, get(client, app, "/health/ready", null).statusCode());
				var denied = get(client, app, "/api/v1/diagnostics", null);
				assertEquals(401, denied.statusCode());
				var streamDenied = get(client, app, "/api/v1/events/stream?after=0", null);
				assertEquals(403, streamDenied.statusCode());
				assertEquals(streamDenied.headers().firstValue("X-Request-ID").orElseThrow(),
						JsonParser.parseString(streamDenied.body()).getAsJsonObject().getAsJsonObject("error").get("requestId").getAsString());
				String id = denied.headers().firstValue("X-Request-ID").orElseThrow();
				assertEquals(id, JsonParser.parseString(denied.body()).getAsJsonObject().getAsJsonObject("error").get("requestId").getAsString());
				assertEquals(403, get(client, app, "/api/v1/diagnostics", login(client, app, "viewer")).statusCode());
				String operator = login(client, app, "issuer");
				var request = HttpRequest.newBuilder(uri(app, "/api/v1/diagnostics"))
						.header("Authorization", "Bearer CANARY-secret").header("Cookie", operator).build();
				assertEquals(401, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
				var diagnostic = get(client, app, "/api/v1/diagnostics", operator);
				assertEquals(diagnostic.body(), 200, diagnostic.statusCode());
				var value = JsonParser.parseString(diagnostic.body()).getAsJsonObject();
				assertFalse(value.getAsJsonObject("geoIp").get("available").getAsBoolean());
				assertEquals(0, value.get("streamConnections").getAsInt());
				assertEquals(0, value.get("queueDisconnects").getAsInt());
				assertEquals(1000, value.getAsJsonObject("streamLimits").get("queueFrames").getAsInt());
				for (String secret : List.of("CANARY", db.url, "password", "missing.mmdb", "127.0.0.1"))
					assertFalse(diagnostic.body(), diagnostic.body().contains(secret));
				try {
					db.available(false);
					assertEquals(503, get(client, app, "/health/ready", null).statusCode());
					assertEquals(200, get(client, app, "/health/live", null).statusCode());
				} finally { db.available(true); }
				await(() -> get(client, app, "/health/ready", null).statusCode() == 200);
			}
		}
	}

	@Test(timeout = 60000)
	public void shutdownDrainsRealBlockedCommitRejectsNewWritesAndClosesReader() throws Exception {
		try (var db = IngestionIT.database(); var client = HttpClient.newHttpClient(); var workers = Executors.newFixedThreadPool(2)) {
			Files.writeString(db.runtime.resolve("config/viewer.properties"), "origin=" + ORIGIN);
			var map = db.runtime.resolve("config/machine-identities.conf");
			Files.writeString(map, "producer=server-a\n");
			Files.setPosixFilePermissions(map, PosixFilePermissions.fromString("rw-------"));
			try (var app = new IntruviaApplication(db.runtime, 0)) {
				app.start();
				var handler = app.agent().getPrivilegeHandler().getPrivilegeHandler();
				var owner = handler.authenticate("producer", "test-password".toCharArray(), false);
				String pat = handler.createPersonalAccessToken(owner, "ops-fixture", ZonedDateTime.now().minusMinutes(1),
						ZonedDateTime.now().plusMinutes(5), Set.of(), Set.of("event:ingest"));
				String body = """
						{"schemaVersion":1,"eventId":"%s","occurredAt":"%s","action":"ban","ip":"203.0.113.42","jail":"CANARY-payload"}
						""".formatted(UUID.randomUUID(), Instant.now());
				var request = HttpRequest.newBuilder(uri(app, "/api/v1/fail2ban/events"))
						.header("Authorization", "Bearer " + pat).header("Content-Type", "application/json")
						.header("X-Request-ID", "CANARY-incoming").timeout(Duration.ofSeconds(20))
						.POST(HttpRequest.BodyPublishers.ofString(body)).build();
				Future<?> closing;
				CompletableFuture<HttpResponse<String>> writing;
				var geo = app.agent().getContainer().getComponent(GeoIpComponent.class);
				var stream = app.agent().getContainer().getComponent(EventStreamComponent.class);
				try (var fault = db.faultConnection(); var statement = fault.createStatement()) {
					fault.setAutoCommit(false);
					statement.execute("LOCK TABLE resources IN ACCESS EXCLUSIVE MODE");
					writing = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
					await(() -> db.hasBlockedWrite());
					closing = workers.submit(() -> { app.close(); return null; });
					await(() -> !app.ready());
					assertFalse(closing.isDone());
					assertEquals(ComponentState.STARTED, geo.getState());
					assertEquals(503, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
					assertEquals(503, get(client, app, "/health/ready", null).statusCode());
					fault.rollback();
					var committed = writing.get(10, TimeUnit.SECONDS);
					assertEquals(201, committed.statusCode());
					String requestId = committed.headers().firstValue("X-Request-ID").orElseThrow();
					assertNotEquals("CANARY-incoming", requestId);
					UUID.fromString(requestId);
				}
				closing.get(15, TimeUnit.SECONDS);
				String log = Files.readString(java.nio.file.Path.of("logs/intruvia.log"));
				for (String secret : List.of(pat, "CANARY-payload", "CANARY-incoming", "synthetic-runtime-test-secret", db.url))
					assertFalse("Sensitive data in operational log", log.contains(secret));
				assertEquals(ComponentState.DESTROYED, geo.getState());
				assertEquals(0, stream.connectionCount());
			}
			try (var restarted = new IntruviaApplication(db.runtime, 0)) {
				restarted.start();
				restarted.agent().getPrivilegeHandler().runAsAgent(context -> {
					try (var tx = restarted.agent().openTx(context.getCertificate(), "ShutdownCheck", true)) {
						assertEquals("1", new li.intruvia.core.persistence.EventRepository().streamState(tx).lastCommittedSequence());
					}
				});
			}
		}
	}

	@Test(timeout = 30000)
	public void shutdownClosesRealAuthenticatedWebSocket() throws Exception {
		try (var db = ViewerSessionIT.database(ORIGIN); var client = HttpClient.newHttpClient();
				var app = new IntruviaApplication(db.runtime, 0)) {
			app.start();
			var hello = new CompletableFuture<Void>();
			var closed = new CompletableFuture<Integer>();
			String cookie = login(client, app, "viewer");
			var socket = client.newWebSocketBuilder().header("Origin", ORIGIN).header("Cookie", cookie)
					.buildAsync(URI.create("ws://127.0.0.1:" + app.port() + "/api/v1/events/stream?after=0"), new WebSocket.Listener() {
						@Override public void onOpen(WebSocket ws) { ws.request(1); }
						@Override public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
							hello.complete(null); ws.request(1); return null;
						}
						@Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
							closed.complete(code); return null;
						}
					}).get(5, TimeUnit.SECONDS);
			hello.get(5, TimeUnit.SECONDS);
			var stream = app.agent().getContainer().getComponent(EventStreamComponent.class);
			assertEquals(1, stream.connectionCount());
			app.close();
			assertEquals(1001, closed.get(5, TimeUnit.SECONDS).intValue());
			assertEquals(0, stream.connectionCount());
			assertTrue(socket.isInputClosed());
		}
	}

	@Test(timeout = 30000)
	public void operatorCountersTrackCommittedDuplicateAndFailedHttpRequests() throws Exception {
		try (var db = IngestionIT.database(); var client = HttpClient.newHttpClient()) {
			Files.writeString(db.runtime.resolve("config/viewer.properties"), "origin=" + ORIGIN);
			var mapping = db.runtime.resolve("config/machine-identities.conf");
			Files.writeString(mapping, "producer=server-a");
			Files.setPosixFilePermissions(mapping, PosixFilePermissions.fromString("rw-------"));
			var roles = db.runtime.resolve("config/PrivilegeRoles.xml");
			Files.writeString(roles, Files.readString(roles).replace("<Role name=\"Producer\">",
					"<Role name=\"Producer\"><Privilege name=\"runtime:diagnostics\" policy=\"DefaultPrivilege\"><AllAllowed>true</AllAllowed></Privilege>"));
			try (var app = new IntruviaApplication(db.runtime, 0)) {
				app.start();
				var handler = app.agent().getPrivilegeHandler().getPrivilegeHandler();
				var owner = handler.authenticate("producer", "test-password".toCharArray(), false);
				String pat = handler.createPersonalAccessToken(owner, "counters", ZonedDateTime.now().minusMinutes(1),
						ZonedDateTime.now().plusMinutes(5), Set.of(), Set.of("event:ingest"));
				String body = """
						{"schemaVersion":1,"eventId":"%s","occurredAt":"%s","action":"ban","ip":"203.0.113.42","jail":"counter"}
						""".formatted(UUID.randomUUID(), Instant.now());
				for (int status : new int[]{201, 200, 409}) {
					String payload = status == 409 ? body.replace("counter", "changed") : body;
					var request = HttpRequest.newBuilder(uri(app, "/api/v1/fail2ban/events"))
							.header("Authorization", "Bearer " + pat).header("Content-Type", "application/json")
							.POST(HttpRequest.BodyPublishers.ofString(payload)).build();
					assertEquals(status, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
				}
				var response = client.send(HttpRequest.newBuilder(uri(app, "/api/v1/session")).header("Origin", ORIGIN)
						.header("X-Intruvia-CSRF", "1").header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"producer\",\"password\":\"test-password\"}")).build(),
						HttpResponse.BodyHandlers.ofString());
				assertEquals(200, response.statusCode());
				String cookie = response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
				var diagnostic = get(client, app, "/api/v1/diagnostics", cookie);
				assertEquals(200, diagnostic.statusCode());
				var counts = JsonParser.parseString(diagnostic.body()).getAsJsonObject().getAsJsonObject("ingestion");
				assertEquals(1, counts.get("created").getAsInt());
				assertEquals(1, counts.get("duplicates").getAsInt());
				assertEquals(1, counts.get("failures").getAsInt());
			}
		}
	}

	@Test(timeout = 30000)
	public void preflightRejectsMissingDbIdentityAndUnboundedSessions() throws Exception {
		try (var db = new DatabaseFixture()) {
			var file = db.runtime.resolve("config/StrolchConfiguration.xml");
			String original = Files.readString(file);
			Files.writeString(file, original.replaceAll("<db.username>[^<]*</db.username>", "<db.username></db.username>"));
			assertTrue(assertThrows(ConfigurationFailure.class, () -> new IntruviaApplication(db.runtime, 0))
					.getMessage().contains("db.username"));
			Files.writeString(file, original.replace("<db.pool.connectionTimeout>3000", "<db.pool.connectionTimeout>0"));
			assertTrue(assertThrows(ConfigurationFailure.class, () -> new IntruviaApplication(db.runtime, 0))
					.getMessage().contains("connectionTimeout"));
			for (String value : List.of("0", "1441", "CANARY-value")) {
				Files.writeString(file, original.replace("<session.maxKeepAlive.minutes>30", "<session.maxKeepAlive.minutes>" + value));
				String message = assertThrows(ConfigurationFailure.class, () -> new IntruviaApplication(db.runtime, 0)).getMessage();
				assertTrue(message.contains("session.maxKeepAlive.minutes"));
				assertFalse(message.contains("CANARY"));
			}
		}
	}

	private static void await(Callable<Boolean> condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!condition.call()) {
			if (System.nanoTime() > deadline) fail("Condition timed out");
			Thread.sleep(20);
		}
	}
	private static URI uri(IntruviaApplication app, String path) { return URI.create("http://127.0.0.1:" + app.port() + path); }
	private static HttpResponse<String> get(HttpClient client, IntruviaApplication app, String path, String cookie) throws Exception {
		var builder = HttpRequest.newBuilder(uri(app, path)).timeout(Duration.ofSeconds(10));
		if (cookie != null) builder.header("Cookie", cookie);
		return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}
	private static String login(HttpClient client, IntruviaApplication app, String user) throws Exception {
		var response = client.send(HttpRequest.newBuilder(uri(app, "/api/v1/session")).header("Origin", ORIGIN)
				.header("X-Intruvia-CSRF", "1").header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + user + "\",\"password\":\"viewer-test-password\"}")).build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(response.body(), 200, response.statusCode());
		return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
	}
}
