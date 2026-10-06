// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import li.intruvia.core.ingest.IngestCommand;
import li.intruvia.core.persistence.EventRepository;
import org.junit.Test;

import static li.intruvia.core.model.ModelConstants.*;
import static org.junit.Assert.*;

/** Exercises the production route, real framework PAT/service authorization and actual PostgreSQL commits. */
public class IngestionHttpIT {
	@Test(timeout = 60000)
	public void httpContractIdentityValidationAndDatabaseFailure() throws Exception {
		try (var database = database(""); var app = new IntruviaApplication(database.runtime, 0);
				var client = HttpClient.newHttpClient()) {
			app.start();
			String token = token(app, "producer", "event:ingest");
			String second = token(app, "second", "event:ingest");
			String payload = payload(UUID.randomUUID(), Instant.now());
			// Authentication takes precedence over malformed/oversized payloads and media type.
			check(send(client, app, null, "x".repeat(20000), "text/plain", false), 401);
			check(send(client, app, "invalid", payload, "application/json", false), 401);
			check(send(client, app, token(app, "producer", "event:read"), payload, "application/json", false), 403);
			check(send(client, app, token(app, "unmapped", "event:ingest"), payload, "application/json", false), 403);
			for (String invalid : List.of("{", payload.replace("sshd", "x".repeat(129)),
					payload.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
					payload.replace("\"jail\":", "\"instanceId\":\"spoofed\",\"jail\":"),
					payload.replace("203.0.113.42", "example.org"),
					payload(UUID.randomUUID(), Instant.now().minusSeconds(604801)),
					payload(UUID.randomUUID(), Instant.now().plusSeconds(600))))
				check(send(client, app, token, invalid, "application/json", false), 400);
			for (String media : new String[]{null, "text/plain", "application/json; charset=ISO-8859-1"})
				check(send(client, app, token, payload, media, false), 415);
			String boundary = payload + " ".repeat(16384 - payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
			var created = check(send(client, app, token, boundary, "application/json; charset=UTF-8", false), 201);
			assertEquals(Set.of("id", "sequence", "duplicate", "geoStatus"), created.keySet());
			assertEquals("NON_PUBLIC", created.get("geoStatus").getAsString());
			assertFalse(created.get("duplicate").getAsBoolean());
			assertTrue(created.get("sequence").getAsJsonPrimitive().isString());
			assertEquals("1", created.get("sequence").getAsString());
			var duplicate = check(send(client, app, token, payload, "application/json", true), 200);
			assertEquals(Set.of("id", "sequence", "duplicate"), duplicate.keySet());
			assertEquals(created.get("id"), duplicate.get("id"));
			assertEquals(created.get("sequence"), duplicate.get("sequence"));
			assertTrue(duplicate.get("duplicate").getAsBoolean());
			check(send(client, app, token, payload.replace("sshd", "changed"), "application/json", false), 409);
			for (boolean chunked : new boolean[]{false, true})
				check(send(client, app, token, boundary + " ", "application/json", chunked), 413);
			var other = check(send(client, app, second, payload, "application/json", false), 201);
			assertEquals("2", other.get("sequence").getAsString());
			assertNotEquals(created.get("id"), other.get("id"));
			assertState(app, 2, "2");
			app.agent().getPrivilegeHandler().runAsAgent(ctx -> {
				try (var tx = app.agent().openTx(ctx.getCertificate(), "HttpIdentity", true)) {
					var events = new EventRepository().events(tx, 10);
					assertEquals(List.of("server-a", "server-b"), events.stream().map(e -> e.source().instanceId()).toList());
					assertEquals(List.of("server-a", "server-b"), events.stream().map(e -> e.observer().id()).toList());
				}
			});
			UUID failedId = UUID.randomUUID();
			String failed = payload(failedId, Instant.now());
			database.rejectReceipt(IngestCommand.receiptId("server-a", failedId));
			check(send(client, app, token, failed, "application/json", false), 503);
			assertState(app, 2, "2");
			database.allowReceipt();
			assertEquals("3", check(send(client, app, token, failed, "application/json", false), 201).get("sequence").getAsString());
			assertState(app, 3, "3");
			// A revocation after successful/cached authentication takes effect at the real endpoint.
			var handler = app.agent().getPrivilegeHandler().getPrivilegeHandler();
			var owner = handler.authenticate("producer", "test-password".toCharArray(), false);
			handler.removePersonalAccessToken(owner, token.split(":", 2)[0]);
			check(send(client, app, token, payload, "application/json", false), 401);
		}
	}

	@Test(timeout = 60000)
	public void concurrentHttpRetriesAndRestartResolveOriginalReceipt() throws Exception {
		try (var database = database(""); var client = HttpClient.newHttpClient()) {
			String payload = payload(UUID.randomUUID(), Instant.now());
			String id;
			try (var app = new IntruviaApplication(database.runtime, 0); var executor = Executors.newFixedThreadPool(8)) {
				app.start();
				String token = token(app, "producer", "event:ingest");
				List<Future<HttpResponse<String>>> futures = new ArrayList<>();
				for (int i = 0; i < 16; i++)
					futures.add(executor.submit(() -> send(client, app, token, payload, "application/json", false)));
				Set<String> ids = new HashSet<>();
				int created = 0;
				for (var future : futures) {
					var response = future.get(15, TimeUnit.SECONDS);
					assertTrue(response.statusCode() == 200 || response.statusCode() == 201);
					if (response.statusCode() == 201) created++;
					ids.add(check(response, response.statusCode()).get("id").getAsString());
				}
				assertEquals(1, created);
				assertEquals(1, ids.size());
				id = ids.iterator().next();
				assertState(app, 1, "1");
			}
			try (var app = new IntruviaApplication(database.runtime, 0)) {
				app.start();
				assertEquals(id, check(send(client, app, token(app, "producer", "event:ingest"), payload,
						"application/json", false), 200).get("id").getAsString());
				assertState(app, 1, "1");
			}
		}
	}

	@Test(timeout = 60000)
	public void perInstanceLimitsSurviveTokenRotationAndRefill() throws Exception {
		try (var database = database("eventsPerSecond=1\nburst=1\n"); var app = new IntruviaApplication(database.runtime, 0);
				var client = HttpClient.newHttpClient()) {
			app.start();
			String token = token(app, "producer", "event:ingest");
			String rotated = token(app, "producer", "event:ingest");
			String second = token(app, "second", "event:ingest");
			String payload = payload(UUID.randomUUID(), Instant.now());
			// Invalid payloads also consume admission tokens; rotation cannot bypass the instance bucket.
			check(send(client, app, token, "{", "application/json", false), 400);
			check(send(client, app, rotated, payload, "application/json", false), 429);
			check(send(client, app, second, payload, "application/json", false), 201);
			Thread.sleep(1100);
			check(send(client, app, rotated, payload, "application/json", false), 201);
			assertState(app, 2, "2");
		}
	}

	@Test(timeout = 60000)
	public void globalLimitsCoverUnauthenticatedRequests() throws Exception {
		try (var database = database("globalPerSecond=1\nglobalBurst=1\n"); var app = new IntruviaApplication(database.runtime, 0);
				var client = HttpClient.newHttpClient()) {
			app.start();
			check(send(client, app, null, "{", "application/json", false), 401);
			check(send(client, app, null, "{", "application/json", false), 429);
			Thread.sleep(1100);
			check(send(client, app, null, "{", "application/json", false), 401);
			assertState(app, 0, "0");
		}
	}

	private static DatabaseFixture database(String limits) throws Exception {
		var database = IngestionIT.database();
		var mapping = database.runtime.resolve("config/machine-identities.conf");
		Files.writeString(mapping, "producer=server-a\nsecond=server-b\n");
		Files.setPosixFilePermissions(mapping, PosixFilePermissions.fromString("rw-------"));
		Files.writeString(database.runtime.resolve("config/ingestion.properties"), limits);
		return database;
	}

	private static String token(IntruviaApplication app, String username, String scope) {
		var handler = app.agent().getPrivilegeHandler().getPrivilegeHandler();
		var owner = handler.authenticate(username, "test-password".toCharArray(), false);
		return handler.createPersonalAccessToken(owner, "http-test", ZonedDateTime.now().minusMinutes(1),
				ZonedDateTime.now().plusMinutes(5), Set.of(), Set.of(scope));
	}

	private static String payload(UUID id, Instant occurred) {
		return """
				{"schemaVersion":1,"eventId":"%s","occurredAt":"%s","action":"ban","ip":"203.0.113.42","jail":"sshd"}
				""".formatted(id, occurred);
	}

	private static HttpResponse<String> send(HttpClient client, IntruviaApplication app, String token, String body,
			String media, boolean chunked) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/api/v1/fail2ban/events?instanceId=spoofed"))
				.timeout(java.time.Duration.ofSeconds(15));
		if (token != null) request.header("Authorization", "Bearer " + token);
		if (media != null) request.header("Content-Type", media);
		var publisher = chunked ? HttpRequest.BodyPublishers.ofInputStream(() ->
				new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))) : HttpRequest.BodyPublishers.ofString(body);
		var response = client.send(request.POST(publisher).build(), HttpResponse.BodyHandlers.ofString());
		if (token != null) assertFalse("Credential reflected", response.body().contains(token));
		return response;
	}

	private static JsonObject check(HttpResponse<String> response, int status) {
		assertEquals(response.body(), status, response.statusCode());
		assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
		assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
		var json = JsonParser.parseString(response.body()).getAsJsonObject();
		if (status >= 400) {
			assertEquals(Set.of("error"), json.keySet());
			var error = json.getAsJsonObject("error");
			assertEquals(Set.of("code", "message", "requestId"), error.keySet());
			UUID.fromString(error.get("requestId").getAsString());
			for (String forbidden : List.of("Exception", "jdbc:", "test-password", "sshd", "203.0.113", "ingest_failure"))
				assertFalse("Sensitive error detail", response.body().contains(forbidden));
		}
		if (status == 429) assertTrue(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow()) >= 1);
		if (status == 401) assertTrue(response.headers().firstValue("WWW-Authenticate").orElseThrow().startsWith("Bearer"));
		return json;
	}

	private static void assertState(IntruviaApplication app, long count, String head) throws Exception {
		app.agent().getPrivilegeHandler().runAsAgent(ctx -> {
			try (var tx = app.agent().openTx(ctx.getCertificate(), "HttpState", true)) {
				assertEquals(count, tx.streamResources(TYPE_SECURITY_EVENT).count());
				assertEquals(count, tx.streamResources(TYPE_INGEST_RECEIPT).count());
				assertEquals(head, new EventRepository().streamState(tx).lastCommittedSequence());
			}
		});
	}
}
