// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import li.intruvia.core.auth.MachineIdentities;
import li.intruvia.core.ingest.*;
import li.intruvia.core.model.*;
import li.intruvia.core.persistence.*;
import li.intruvia.core.retention.*;
import li.intruvia.core.read.*;
import li.strolch.privilege.model.Certificate;
import li.strolch.service.api.*;
import org.junit.Test;
import static li.intruvia.core.model.ModelConstants.*;
import static li.intruvia.core.ingest.IngestResult.Outcome.*;
import static org.junit.Assert.*;

public class RetentionIT {
	private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
	private static final String ORIGIN = "https://intruvia.example.org";
	private static final MachineIdentities IDENTITIES = new MachineIdentities(Map.of("producer", "server-a"));

	@Test(timeout = 60000)
	public void countAgeReceiptExpiryAndRestartUseIndependentBounds() throws Exception {
		try (var database = database(2, 10, 86400)) {
			Fail2banEvent first = payload(NOW);
			UUID original;
			try (var app = new IntruviaApplication(database.runtime, 0)) {
				app.start();
				var cert = certificate(app);
				original = ingest(app, cert, first, NOW).accepted().id();
				assertEquals(CREATED, ingest(app, cert, payload(NOW.plusSeconds(1)), NOW.plusSeconds(1)).outcome());
				assertEquals(CREATED, ingest(app, cert, payload(NOW.plusSeconds(2)), NOW.plusSeconds(2)).outcome());
				assertState(app, 2, 3, "3", "1");
				var duplicate = ingest(app, cert, first, NOW.plus(Duration.ofDays(8)));
				assertEquals(DUPLICATE, duplicate.outcome());
				assertEquals(original, duplicate.accepted().id());
				assertEquals("1", duplicate.accepted().sequence());
				assertNull(duplicate.accepted().geoStatus());
				var changed = new Fail2banEvent(first.eventId(), NOW, first.ip(), "changed", null);
				assertEquals(CONFLICT, ingest(app, cert, changed, NOW.plus(Duration.ofDays(8))).outcome());
				sweep(app, NOW.plus(Duration.ofDays(30)).plusSeconds(1));
				// Exactly expired receipts 1/2 removed; receipt 3 remains for one more second.
				assertState(app, 1, 1, "3", "2");
				assertEquals(INVALID, ingest(app, cert, first, NOW.plus(Duration.ofDays(30)).plusSeconds(1)).outcome());
			}
			try (var app = new IntruviaApplication(database.runtime, 0)) {
				app.start();
				assertState(app, 1, 1, "3", "2");
				sweep(app, NOW.plus(Duration.ofDays(30)).plusSeconds(2));
				assertState(app, 0, 0, "3", "3");
				var cert = certificate(app);
				assertEquals("4", ingest(app, cert, payload(NOW.plus(Duration.ofDays(31))),
						NOW.plus(Duration.ofDays(31))).accepted().sequence());
				assertState(app, 1, 1, "4", "3");
			}
		}
	}

	@Test(timeout = 60000)
	public void exactAgeBoundaryAndClockReversalRemoveOnlyAPrefix() throws Exception {
		try (var database = database(10, 10, 86400); var app = new IntruviaApplication(database.runtime, 0)) {
			app.start();
			var cert = certificate(app);
			assertEquals(CREATED, ingest(app, cert, payload(NOW), NOW).outcome());
			assertEquals(CREATED, ingest(app, cert, payload(NOW.plusSeconds(10)), NOW.plusSeconds(10)).outcome());
			sweep(app, NOW.plus(Duration.ofDays(30)).minusNanos(1));
			assertState(app, 2, 2, "2", "0");
			sweep(app, NOW.plus(Duration.ofDays(30)));
			assertState(app, 1, 1, "2", "1");
			// Later sequence has an older receivedAt after a clock reversal. Prefix removal includes sequence 2.
			assertEquals(CREATED, ingest(app, cert, payload(NOW), NOW).outcome());
			sweep(app, NOW.plus(Duration.ofDays(30)));
			assertState(app, 0, 1, "3", "3");
		}
	}

	@Test(timeout = 60000)
	public void httpCapacityPreservesReceiptsAndExpiredHistoryReturns410() throws Exception {
		try (var database = database(1, 2, 86400); var app = new IntruviaApplication(database.runtime, 0);
				var client = HttpClient.newHttpClient()) {
			app.start();
			var handler = app.agent().getPrivilegeHandler().getPrivilegeHandler();
			var owner = handler.authenticate("producer", "test-password".toCharArray(), false);
			String token = handler.createPersonalAccessToken(owner, "retention-http", ZonedDateTime.now().minusMinutes(1),
					ZonedDateTime.now().plusMinutes(5), Set.of(), Set.of("event:ingest"));
			String body = json(payload(Instant.now()));
			var first = post(client, app, token, body, 201);
			post(client, app, token, json(payload(Instant.now())), 201);
			assertState(app, 1, 2, "2", "1");
			var retry = post(client, app, token, body, 200);
			assertEquals(first.get("id"), retry.get("id"));
			assertEquals(first.get("sequence"), retry.get("sequence"));
			var full = post(client, app, token, json(payload(Instant.now())), 429);
			assertEquals("receipt_capacity", full.getAsJsonObject("error").get("code").getAsString());
			post(client, app, token, body.replace("sshd", "changed"), 409);
			assertState(app, 1, 2, "2", "1");
			String cookie = login(client, app);
			var response = get(client, app, cookie, "/events?after=0", 410);
			assertEquals("resync_required", response.getAsJsonObject("error").get("code").getAsString());
			var snapshot = get(client, app, cookie, "/events/snapshot", 200);
			assertEquals("1", snapshot.get("minAfter").getAsString());
			assertEquals("2", snapshot.get("cursor").getAsString());
			assertEquals(1, snapshot.getAsJsonArray("items").size());
			assertEquals(1, get(client, app, cookie, "/events?after=1", 200).getAsJsonArray("items").size());
			// Force real expiry in the maintenance clock; a later new producer event can be admitted.
			sweep(app, Instant.now().plus(Duration.ofDays(31)));
			post(client, app, token, body, 201);
			assertState(app, 1, 1, "3", "2");
		}
	}

	@Test(timeout = 60000)
	public void failedAdmissionRollsBackPruningAndReceiptCleanup() throws Exception {
		try (var database = database(1, 2, 86400); var app = new IntruviaApplication(database.runtime, 0)) {
			app.start();
			var cert = certificate(app);
			Fail2banEvent first = payload(NOW);
			assertEquals(CREATED, ingest(app, cert, first, NOW).outcome());
			Fail2banEvent failed = payload(NOW.plus(Duration.ofDays(31)));
			database.rejectReceipt(IngestCommand.receiptId("server-a", failed.eventId()));
			assertEquals(UNAVAILABLE, ingest(app, cert, failed, failed.occurredAt()).outcome());
			assertState(app, 1, 1, "1", "0");
			database.allowReceipt();
			assertEquals(CREATED, ingest(app, cert, failed, failed.occurredAt()).outcome());
			assertState(app, 1, 1, "2", "1");
		}
	}

	@Test(timeout = 60000)
	public void concurrentReadersPruningAndAdmissionNeverSkipADeletedRange() throws Exception {
		try (var database = database(3, 50, 86400); var app = new IntruviaApplication(database.runtime, 0);
				var executor = Executors.newFixedThreadPool(3)) {
			app.start();
			var cert = certificate(app);
			var viewer = app.agent().getPrivilegeHandler().getPrivilegeHandler()
					.authenticate("producer", "test-password".toCharArray(), false);
			for (int i = 1; i <= 20; i++) {
				var barrier = new CyclicBarrier(3);
				var write = executor.submit(() -> { barrier.await(); return ingest(app, cert, payload(NOW), NOW); });
				var snapshot = executor.submit(() -> {
					barrier.await();
					return services(app).doService(viewer, new EventReadService(), new EventReadArgument(true, 0, null, 1000));
				});
				var history = executor.submit(() -> {
					barrier.await();
					return services(app).doService(viewer, new EventReadService(), new EventReadArgument(false, 0, null, 1000));
				});
				assertEquals(CREATED, write.get(10, TimeUnit.SECONDS).outcome());
				var result = snapshot.get(10, TimeUnit.SECONDS);
				assertEquals(EventReadResult.Outcome.OK, result.outcome());
				var window = (EventReadResult.Snapshot) result.body();
				long head = Long.parseLong(window.cursor());
				long min = Long.parseLong(window.minAfter());
				assertEquals(Math.max(0, head - 3), min);
				assertEquals(head - min, window.items().size());
				for (int index = 0; index < window.items().size(); index++)
					assertEquals(Long.toString(min + index + 1), window.items().get(index).sequence());
				var page = history.get(10, TimeUnit.SECONDS);
				if (page.outcome() == EventReadResult.Outcome.OK) {
					var value = (EventReadResult.Page) page.body();
					assertTrue(Long.parseLong(value.through()) <= 3);
					assertEquals(Long.parseLong(value.through()), value.items().size());
				} else {
					assertEquals(EventReadResult.Outcome.EXPIRED, page.outcome());
				}
			}
			assertState(app, 3, 20, "20", "17");
			// At receipt capacity, concurrent distinct submissions cannot over-admit.
			var futures = new ArrayList<Future<IngestResult>>();
			for (int i = 0; i < 40; i++)
				futures.add(executor.submit(() -> ingest(app, cert, payload(NOW), NOW)));
			int created = 0;
			for (var future : futures) {
				var outcome = future.get(10, TimeUnit.SECONDS).outcome();
				assertTrue(outcome == CREATED || outcome == CAPACITY);
				if (outcome == CREATED) created++;
			}
			assertEquals(30, created);
			assertState(app, 3, 50, "50", "47");
		}
	}

	@Test(timeout = 60000)
	public void scheduledSweepRunsAndMachineCannotInvokeMaintenance() throws Exception {
		try (var database = database(10, 10, 1)) {
			var config = database.runtime.resolve("config/StrolchConfiguration.xml");
			Files.writeString(config, Files.readString(config).replace("<eventAgeSeconds>2592000", "<eventAgeSeconds>1"));
			try (var app = new IntruviaApplication(database.runtime, 0)) {
				app.start();
				var cert = certificate(app);
				assertFalse(services(app).doService(cert, new RetentionService(RetentionPolicy.DEFAULT, Clock.systemUTC()),
						new ServiceArgument()).isOk());
				Instant old = Instant.now().minusSeconds(3);
				assertEquals(CREATED, ingest(app, cert, payload(old), old).outcome());
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
				while (snapshotCount(app) != 0 && System.nanoTime() < deadline) Thread.sleep(50);
				assertState(app, 0, 1, "1", "1");
			}
		}
		assertFalse(Thread.getAllStackTraces().keySet().stream().anyMatch(thread -> thread.getName().equals("intruvia-retention")));
	}

	private static DatabaseFixture database(int events, int receipts, long sweep) throws Exception {
		var database = IngestionIT.database();
		var config = database.runtime.resolve("config/StrolchConfiguration.xml");
		Files.writeString(config, Files.readString(config).replace("<eventCapacity>100000", "<eventCapacity>" + events)
				.replace("<receiptCapacity>1000000", "<receiptCapacity>" + receipts)
				.replace("<sweepSeconds>86400", "<sweepSeconds>" + sweep));
		Files.writeString(database.runtime.resolve("config/viewer.properties"), "origin=" + ORIGIN + "\n");
		var mapping = database.runtime.resolve("config/machine-identities.conf");
		Files.writeString(mapping, "producer=server-a\n");
		Files.setPosixFilePermissions(mapping, PosixFilePermissions.fromString("rw-------"));
		return database;
	}
	private static Certificate certificate(IntruviaApplication app) {
		var handler = app.agent().getPrivilegeHandler().getPrivilegeHandler();
		var owner = handler.authenticate("producer", "test-password".toCharArray(), false);
		String token = handler.createPersonalAccessToken(owner, "retention-test", ZonedDateTime.now().minusMinutes(1),
				ZonedDateTime.now().plusMinutes(5), Set.of(), Set.of("event:ingest"));
		return handler.authenticatePersonalAccessToken(token, "retention-test");
	}
	private static ServiceHandler services(IntruviaApplication app) { return app.agent().getContainer().getComponent(ServiceHandler.class); }
	private static Fail2banEvent payload(Instant now) { return new Fail2banEvent(UUID.randomUUID(), now, "203.0.113.42", "sshd", null); }
	private static IngestResult ingest(IntruviaApplication app, Certificate cert, Fail2banEvent event, Instant now) {
		return services(app).doService(cert, new IngestService(IDENTITIES, Clock.fixed(now, ZoneOffset.UTC), EventAgePolicy.DEFAULT, null),
				new IngestArgument(event));
	}
	private static void sweep(IntruviaApplication app, Instant now) throws Exception {
		app.agent().getPrivilegeHandler().runAsAgent(ctx -> assertTrue(services(app).doService(ctx.getCertificate(),
				new RetentionService(app.agent().getContainer().getComponent(RetentionComponent.class).policy(), Clock.fixed(now, ZoneOffset.UTC)),
				new ServiceArgument()).isOk()));
	}
	private static long snapshotCount(IntruviaApplication app) throws Exception {
		return app.agent().getPrivilegeHandler().runAsAgentWithResult(ctx -> {
			try (var tx = app.agent().openTx(ctx.getCertificate(), "Count", true)) {
				EventStreamLock.acquire(tx);
				return tx.streamResources(TYPE_SECURITY_EVENT).count();
			}
		});
	}
	private static void assertState(IntruviaApplication app, long events, long receipts, String head, String min) throws Exception {
		app.agent().getPrivilegeHandler().runAsAgent(ctx -> {
			try (var tx = app.agent().openTx(ctx.getCertificate(), "RetentionState", true)) {
				EventStreamLock.acquire(tx);
				assertEquals(events, tx.streamResources(TYPE_SECURITY_EVENT).count());
				assertEquals(receipts, tx.streamResources(TYPE_INGEST_RECEIPT).count());
				var state = new EventRepository().streamState(tx);
				assertEquals(head, state.lastCommittedSequence());
				assertEquals(min, state.minAfter());
			}
		});
	}
	private static String json(Fail2banEvent event) {
		return "{\"schemaVersion\":1,\"eventId\":\"" + event.eventId() + "\",\"occurredAt\":\"" + event.occurredAt() +
				"\",\"action\":\"ban\",\"ip\":\"203.0.113.42\",\"jail\":\"sshd\"}";
	}
	private static JsonObject post(HttpClient client, IntruviaApplication app, String token, String body, int status) throws Exception {
		var request = HttpRequest.newBuilder(URI.create(base(app) + "/fail2ban/events"))
				.header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body)).build();
		var response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals(response.body(), status, response.statusCode());
		assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
		if (status == 429) assertEquals("86400", response.headers().firstValue("Retry-After").orElseThrow());
		assertFalse(response.body().contains(token));
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}
	private static String base(IntruviaApplication app) { return "http://127.0.0.1:" + app.port() + "/api/v1"; }
	private static String login(HttpClient client, IntruviaApplication app) throws Exception {
		var request = HttpRequest.newBuilder(URI.create(base(app) + "/session"))
				.header("Origin", ORIGIN).header("X-Intruvia-CSRF", "1").header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"producer\",\"password\":\"test-password\"}")).build();
		var response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals(response.body(), 200, response.statusCode());
		return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
	}
	private static JsonObject get(HttpClient client, IntruviaApplication app, String cookie, String path, int status) throws Exception {
		var response = client.send(HttpRequest.newBuilder(URI.create(base(app) + path)).header("Cookie", cookie).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(response.body(), status, response.statusCode());
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}
}
