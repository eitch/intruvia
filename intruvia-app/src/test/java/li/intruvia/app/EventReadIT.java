// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import li.intruvia.core.auth.MachineIdentities;
import li.intruvia.core.ingest.*;
import li.intruvia.core.model.*;
import li.intruvia.core.persistence.*;
import li.intruvia.core.read.*;
import li.strolch.service.api.ServiceHandler;
import org.junit.Test;

import static li.intruvia.core.model.ModelConstants.*;
import static org.junit.Assert.*;

public class EventReadIT {
	private static final String ORIGIN = "https://intruvia.example.org";

	@Test(timeout = 60000)
	public void boundedSnapshotsPaginationAndCursorValidation() throws Exception {
		try (var database = ViewerSessionIT.database(ORIGIN); var app = new IntruviaApplication(database.runtime, 0);
				var client = HttpClient.newHttpClient()) {
			app.start();
			String cookie = login(client, app, "viewer");
			JsonObject empty = get(client, app, cookie, "/events/snapshot", 200);
			assertEquals(0, empty.getAsJsonArray("items").size());
			assertString(empty, "cursor", "0");
			assertString(empty, "minAfter", "0");
			assertFalse(empty.get("truncated").getAsBoolean());
			JsonObject emptyPage = get(client, app, cookie, "/events?after=0", 200);
			assertString(emptyPage, "nextAfter", "0");
			assertString(emptyPage, "through", "0");
			assertFalse(emptyPage.get("hasMore").getAsBoolean());
			seed(app, 1, 1005, 0);
			JsonObject snapshot = get(client, app, cookie, "/events/snapshot", 200);
			assertEquals(Set.of("items", "cursor", "minAfter", "truncated"), snapshot.keySet());
			assertEquals(1000, snapshot.getAsJsonArray("items").size());
			assertSequences(snapshot, 6, 1005);
			assertString(snapshot, "cursor", "1005");
			assertTrue(snapshot.get("truncated").getAsBoolean());
			assertSequences(get(client, app, cookie, "/events/snapshot?limit=1", 200), 1005, 1005);
			JsonObject first = get(client, app, cookie, "/events?after=0", 200);
			assertEquals(Set.of("items", "nextAfter", "hasMore", "through"), first.keySet());
			assertSequences(first, 1, 500);
			assertString(first, "through", "1005");
			seed(app, 1006, 1010, 0);
			JsonObject second = get(client, app, cookie, "/events?after=500&through=1005", 200);
			assertSequences(second, 501, 1000);
			assertTrue(second.get("hasMore").getAsBoolean());
			JsonObject third = get(client, app, cookie, "/events?after=1000&through=1005", 200);
			assertSequences(third, 1001, 1005);
			assertFalse(third.get("hasMore").getAsBoolean());
			JsonObject end = get(client, app, cookie, "/events?after=1005&through=1005", 200);
			assertString(end, "nextAfter", "1005");
			assertEquals(0, end.getAsJsonArray("items").size());
			assertSequences(get(client, app, cookie, "/events?after=1005", 200), 1006, 1010);
			assertSequences(get(client, app, cookie, "/events?after=0&limit=1000", 200), 1, 1000);
			for (String value : List.of("", "-1", "01", "+1", "1.0", "abc", "9223372036854775808", "999999999999999999999")) {
				get(client, app, cookie, "/events?after=" + value, 400);
				get(client, app, cookie, "/events?after=0&through=" + value, 400);
			}
			for (String limit : List.of("0", "1001", "-1", "", "1.5", "2147483648")) {
				get(client, app, cookie, "/events?after=0&limit=" + limit, 400);
				get(client, app, cookie, "/events/snapshot?limit=" + limit, 400);
			}
			for (String path : List.of("/events", "/events?after=1011", "/events?after=0&through=1011",
					"/events?after=3&through=2", "/events?after=0&after=1", "/events?after=0&limit=1&limit=2",
					"/events?after=0&through=1&through=2", "/events/snapshot?after=0", "/events?after=0&offset=1"))
				get(client, app, cookie, path, 400);
			prune(app, 500);
			assertEquals("resync_required", get(client, app, cookie, "/events?after=499", 410)
					.getAsJsonObject("error").get("code").getAsString());
			get(client, app, cookie, "/events?after=0&through=1005", 410);
			assertSequences(get(client, app, cookie, "/events?after=500&limit=1", 200), 501, 501);
			JsonObject retained = get(client, app, cookie, "/events/snapshot", 200);
			assertSequences(retained, 501, 1010);
			assertString(retained, "minAfter", "500");
			assertFalse(retained.get("truncated").getAsBoolean());
			prune(app, 1010);
			JsonObject removed = get(client, app, cookie, "/events/snapshot", 200);
			assertString(removed, "cursor", "1010");
			assertString(removed, "minAfter", "1010");
			assertEquals(0, removed.getAsJsonArray("items").size());
			get(client, app, cookie, "/events?after=1009", 410);
			get(client, app, cookie, "/events?after=1010", 200);
		}
	}

	@Test(timeout = 60000)
	public void fullWidthSequencesSurviveHttpAndRestart() throws Exception {
		long start = 9007199254740992L;
		try (var database = ViewerSessionIT.database(ORIGIN)) {
			try (var app = new IntruviaApplication(database.runtime, 0); var client = HttpClient.newHttpClient()) {
				app.start();
				seed(app, start + 1, start + 3, start);
				String cookie = login(client, app, "viewer");
				JsonObject snapshot = get(client, app, cookie, "/events/snapshot", 200);
				assertSequences(snapshot, start + 1, start + 3);
				assertString(snapshot, "cursor", Long.toString(start + 3));
				assertString(snapshot, "minAfter", Long.toString(start));
				get(client, app, cookie, "/events?after=" + (start - 1), 410);
			}
			try (var app = new IntruviaApplication(database.runtime, 0); var client = HttpClient.newHttpClient()) {
				app.start();
				String cookie = login(client, app, "viewer");
				assertSequences(get(client, app, cookie, "/events?after=" + start + "&limit=2", 200), start + 1, start + 2);
				assertSequences(get(client, app, cookie, "/events?after=" + (start + 2), 200), start + 3, start + 3);
				prune(app, start + 3);
				seed(app, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE - 1);
				JsonObject maximum = get(client, app, cookie, "/events?after=" + (Long.MAX_VALUE - 1), 200);
				assertString(maximum, "through", Long.toString(Long.MAX_VALUE));
				assertString(maximum, "nextAfter", Long.toString(Long.MAX_VALUE));
				assertSequences(maximum, Long.MAX_VALUE, Long.MAX_VALUE);
				assertFalse(maximum.get("hasMore").getAsBoolean());
			}
		}
	}

	@Test(timeout = 60000)
	public void realConcurrentIngestionProducesConsistentSnapshotsAndStablePages() throws Exception {
		try (var database = IngestionIT.database(); var app = new IntruviaApplication(database.runtime, 0);
				var executor = Executors.newFixedThreadPool(2)) {
			app.start();
			var handler = app.agent().getPrivilegeHandler().getPrivilegeHandler();
			var owner = handler.authenticate("producer", "test-password".toCharArray(), false);
			String token = handler.createPersonalAccessToken(owner, "read-test", ZonedDateTime.now().minusMinutes(1),
					ZonedDateTime.now().plusMinutes(5), Set.of(), Set.of("event:ingest", "event:read"));
			var certificate = handler.authenticatePersonalAccessToken(token, "read-test");
			var services = app.agent().getContainer().getComponent(ServiceHandler.class);
			var identities = new MachineIdentities(Map.of("producer", "server-a"));
			CountDownLatch captured = new CountDownLatch(1);
			CountDownLatch finish = new CountDownLatch(1);
			CyclicBarrier racing = new CyclicBarrier(2);
			var writer = executor.submit(() -> {
				for (int i = 1; i <= 80; i++) {
					if (i > 40) await(racing);
					var payload = new Fail2banEvent(UUID.randomUUID(), Instant.now(), "2001:db8::1", "sshd", null);
					var result = services.doService(certificate, new IngestService(identities), new IngestArgument(payload));
					assertEquals(IngestResult.Outcome.CREATED, result.outcome());
					if (i == 40) { captured.countDown(); await(finish); }
					if (i > 40) await(racing);
				}
			});
			try {
				assertTrue(captured.await(20, TimeUnit.SECONDS));
				var first = (EventReadResult.Page) read(services, certificate, false, 0, null, 7).body();
				assertEquals("40", first.through());
				finish.countDown();
				List<String> seen = new ArrayList<>();
				EventReadResult.Page page = first;
				do {
					seen.addAll(page.items().stream().map(SecurityEvent::sequence).toList());
					if (!page.hasMore()) break;
					page = (EventReadResult.Page) read(services, certificate, false, Long.parseLong(page.nextAfter()), 40L, 7).body();
				} while (true);
				assertEquals(40, seen.size());
				for (int i = 0; i < 40; i++) assertEquals(Integer.toString(i + 1), seen.get(i));
				for (int i = 0; i < 40; i++) {
					await(racing);
					var snapshot = (EventReadResult.Snapshot) read(services, certificate, true, 0, null, 11).body();
					long head = Long.parseLong(snapshot.cursor());
					assertTrue(head == 40 + i || head == 41 + i);
					assertEquals(11, snapshot.items().size());
					assertTrue(snapshot.truncated());
					for (int j = 0; j < 11; j++) assertEquals(Long.toString(head - 10 + j), snapshot.items().get(j).sequence());
					await(racing);
				}
				writer.get(20, TimeUnit.SECONDS);
				var tail = (EventReadResult.Page) read(services, certificate, false, 40, null, 1000).body();
				assertEquals(40, tail.items().size());
				assertEquals("80", tail.through());
				assertFalse(tail.hasMore());
			} finally { finish.countDown(); }
		}
	}

	@Test(timeout = 60000)
	public void readRoutesRequireViewerCookiesAndReadPolicy() throws Exception {
		try (var database = ViewerSessionIT.database(ORIGIN); var app = new IntruviaApplication(database.runtime, 0);
				var client = HttpClient.newHttpClient()) {
			app.start();
			String cookie = login(client, app, "viewer");
			var handler = app.agent().getPrivilegeHandler().getPrivilegeHandler();
			var owner = handler.authenticate("issuer", "viewer-test-password".toCharArray(), false);
			String pat = handler.createPersonalAccessToken(owner, "read-route", ZonedDateTime.now().minusMinutes(1),
					ZonedDateTime.now().plusMinutes(5), Set.of(), Set.of("event:read"));
			var api = handler.authenticatePersonalAccessToken(pat, "intruvia-viewer");
			var denied = handler.authenticate("denied", "viewer-test-password".toCharArray(), "intruvia-viewer",
					li.strolch.privilege.model.Usage.ANY, false);
			var policy = handler.authenticate("policydenied", "viewer-test-password".toCharArray(), "intruvia-viewer",
					li.strolch.privilege.model.Usage.ANY, false);
			for (String path : List.of("/events/snapshot", "/events?after=0")) {
				get(client, app, "", path, 401);
				get(client, app, "IntruviaSession=" + pat, path, 401);
				get(client, app, "IntruviaSession=" + api.getAuthToken(), path, 401);
				get(client, app, "IntruviaSession=" + denied.getAuthToken(), path, 403);
				get(client, app, "IntruviaSession=" + policy.getAuthToken(), path, 403);
				get(client, app, cookie, path, 200);
				var request = HttpRequest.newBuilder(uri(app, path)).header("Cookie", cookie)
						.header("Authorization", "Bearer " + pat).GET().build();
				assertEquals(401, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
				request = HttpRequest.newBuilder(uri(app, path)).header("Cookie", cookie).header("Origin", "https://evil.example").GET().build();
				assertEquals(403, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
			}
			var certificate = handler.authenticate("viewer", "viewer-test-password".toCharArray(), false);
			assertEquals(EventReadResult.Outcome.INVALID, readResult(app, certificate, new EventReadArgument(false, -1, null, 1)).outcome());
			var realm = new EventReadArgument(true, 0, null, 1);
			realm.realm = "defaultRealm";
			assertEquals(EventReadResult.Outcome.INVALID, readResult(app, certificate, realm).outcome());
			assertFalse(readResult(app, denied, new EventReadArgument(true, 0, null, 1)).isOk());
			Thread.currentThread().interrupt();
			try { assertEquals(EventReadResult.Outcome.UNAVAILABLE, readResult(app, certificate,
					new EventReadArgument(true, 0, null, 1)).outcome()); }
			finally { Thread.interrupted(); }
			var logout = HttpRequest.newBuilder(uri(app, "/session")).header("Cookie", cookie).header("Origin", ORIGIN)
					.header("X-Intruvia-CSRF", "1").DELETE().build();
			assertEquals(204, client.send(logout, HttpResponse.BodyHandlers.ofString()).statusCode());
			get(client, app, cookie, "/events/snapshot", 401);
			get(client, app, cookie, "/events?after=0", 401);
		}
	}

	@Test(timeout = 60000)
	public void waitingReadsObserveAtomicPrefixRemovalAndExpiry() throws Exception {
		var time = new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
		Clock clock = new Clock() {
			@Override public ZoneId getZone() { return ZoneOffset.UTC; }
			@Override public Clock withZone(ZoneId zone) { return this; }
			@Override public Instant instant() { return time.get(); }
		};
		try (var database = ViewerSessionIT.database(ORIGIN); var app = new IntruviaApplication(database.runtime, 0, clock);
				var client = HttpClient.newHttpClient(); var executor = Executors.newFixedThreadPool(3)) {
			app.start();
			seed(app, 1, 3, 0);
			String cookie = login(client, app, "viewer");
			var handler = app.agent().getPrivilegeHandler().getPrivilegeHandler();
			var certificate = handler.authenticate("viewer", "viewer-test-password".toCharArray(), false);
			CountDownLatch locked = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			var removal = executor.submit(() -> {
				app.agent().getPrivilegeHandler().runAsAgent(ctx -> {
					try (var tx = app.agent().openTx(ctx.getCertificate(), "AtomicReadFixture", false).rollbackOnFailure()) {
						EventStreamLock.acquire(tx);
						tx.streamResources(TYPE_SECURITY_EVENT).filter(event -> event.getLong(BAG_ENVELOPE, PARAM_SEQUENCE) <= 2)
								.toList().forEach(tx::remove);
						new EventRepository().updateStreamState(tx, new EventStreamState("3", "2"));
						locked.countDown();
						await(release);
						tx.commitOnClose();
					}
				});
				return true;
			});
			try {
				assertTrue(locked.await(10, TimeUnit.SECONDS));
				CountDownLatch reading = new CountDownLatch(2);
				var snapshot = executor.submit(() -> {
					reading.countDown();
					return readResult(app, certificate, new EventReadArgument(true, 0, null, 1000));
				});
				var page = executor.submit(() -> {
					reading.countDown();
					return readResult(app, certificate, new EventReadArgument(false, 1, 3L, 1000));
				});
				assertTrue(reading.await(10, TimeUnit.SECONDS));
				assertThrows(TimeoutException.class, () -> snapshot.get(100, TimeUnit.MILLISECONDS));
				assertThrows(TimeoutException.class, () -> page.get(100, TimeUnit.MILLISECONDS));
				release.countDown();
				assertTrue(removal.get(10, TimeUnit.SECONDS));
				var value = (EventReadResult.Snapshot) snapshot.get(10, TimeUnit.SECONDS).body();
				assertEquals("3", value.cursor());
				assertEquals("2", value.minAfter());
				assertEquals(List.of("3"), value.items().stream().map(SecurityEvent::sequence).toList());
				assertEquals(EventReadResult.Outcome.EXPIRED, page.get(10, TimeUnit.SECONDS).outcome());
			} finally { release.countDown(); }
			var token = cookie.substring(cookie.indexOf('=') + 1);
			var session = handler.validate(token);
			time.set(session.getCertificate().getLoginTime().toInstant().plusSeconds(1800));
			get(client, app, cookie, "/events/snapshot", 401);
			get(client, app, cookie, "/events?after=2", 401);
		}
	}

	private static EventReadResult readResult(IntruviaApplication app, li.strolch.privilege.model.Certificate certificate,
			EventReadArgument argument) {
		return app.agent().getContainer().getComponent(ServiceHandler.class).doService(certificate, new EventReadService(), argument);
	}
	private static EventReadResult read(ServiceHandler services, li.strolch.privilege.model.Certificate certificate,
			boolean snapshot, long after, Long through, int limit) {
		var result = services.doService(certificate, new EventReadService(), new EventReadArgument(snapshot, after, through, limit));
		assertEquals(result.getMessage(), EventReadResult.Outcome.OK, result.outcome());
		return result;
	}
	private static void seed(IntruviaApplication app, long first, long last, long minimum) throws Exception {
		app.agent().getPrivilegeHandler().runAsAgent(ctx -> {
			try (var tx = app.agent().openTx(ctx.getCertificate(), "ReadFixture", false).rollbackOnFailure()) {
				EventStreamLock.acquire(tx);
				var repository = new EventRepository();
				for (long sequence = first;; sequence++) {
					var payload = new Fail2banEvent(UUID.randomUUID(), Instant.now(), "2001:db8::1", "<script>plain</script>", null);
					var geo = new SecurityEvent.Geo(GeoStatus.UNAVAILABLE, null, null, null, null, null, null, null, null, null, null, null);
					repository.addEvent(tx, payload.toEvent("server-a", UUID.randomUUID(), sequence, Instant.now(), geo));
					if (sequence == last) break;
				}
				repository.updateStreamState(tx, new EventStreamState(Long.toString(last), Long.toString(minimum)));
				tx.commitOnClose();
			}
		});
	}
	/** Test-only prefix deletion under the production lock; automatic retention is task 013. */
	private static void prune(IntruviaApplication app, long through) throws Exception {
		app.agent().getPrivilegeHandler().runAsAgent(ctx -> {
			try (var tx = app.agent().openTx(ctx.getCertificate(), "ReadPruningFixture", false).rollbackOnFailure()) {
				EventStreamLock.acquire(tx);
				var repository = new EventRepository();
				var state = repository.streamState(tx);
				tx.streamResources(TYPE_SECURITY_EVENT).filter(event -> event.getLong(BAG_ENVELOPE, PARAM_SEQUENCE) <= through)
						.toList().forEach(tx::remove);
				repository.updateStreamState(tx, new EventStreamState(state.lastCommittedSequence(), Long.toString(through)));
				tx.commitOnClose();
			}
		});
	}
	private static void await(CyclicBarrier barrier) {
		try { barrier.await(10, TimeUnit.SECONDS); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
		catch (BrokenBarrierException | TimeoutException e) { throw new AssertionError(e); }
	}
	private static void await(CountDownLatch latch) {
		try { assertTrue(latch.await(20, TimeUnit.SECONDS)); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
	}
	private static URI uri(IntruviaApplication app, String path) {
		return URI.create("http://127.0.0.1:" + app.port() + "/api/v1" + path);
	}
	private static String login(HttpClient client, IntruviaApplication app, String username) throws Exception {
		var request = HttpRequest.newBuilder(uri(app, "/session")).header("Origin", ORIGIN).header("X-Intruvia-CSRF", "1")
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
						"{\"username\":\"" + username + "\",\"password\":\"viewer-test-password\"}")).build();
		var response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals(response.body(), 200, response.statusCode());
		return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
	}
	private static JsonObject get(HttpClient client, IntruviaApplication app, String cookie, String path, int status) throws Exception {
		var request = HttpRequest.newBuilder(uri(app, path)).header("Cookie", cookie).timeout(Duration.ofSeconds(10)).GET().build();
		var response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals(response.body(), status, response.statusCode());
		assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
		JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
		if (status >= 400) {
			var error = json.getAsJsonObject("error");
			assertEquals(Set.of("code", "message", "requestId"), error.keySet());
			UUID.fromString(error.get("requestId").getAsString());
			assertFalse(response.body().contains("Exception"));
		}
		return json;
	}
	private static void assertString(JsonObject json, String key, String expected) {
		assertTrue(json.getAsJsonPrimitive(key).isString());
		assertEquals(expected, json.get(key).getAsString());
	}
	private static void assertSequences(JsonObject json, long first, long last) {
		var items = json.getAsJsonArray("items");
		assertEquals(last - first + 1, items.size());
		for (int i = 0; i < items.size(); i++) {
			var event = items.get(i).getAsJsonObject();
			assertString(event, "sequence", Long.toString(first + i));
			assertEquals(Set.of("schemaVersion", "id", "sequence", "source", "category", "action", "occurredAt", "receivedAt",
					"subject", "observer", "attributes", "geo"), event.keySet());
			assertEquals(1, event.get("schemaVersion").getAsInt());
			assertEquals("2001:db8::1", event.getAsJsonObject("subject").get("ip").getAsString());
			assertTrue(event.getAsJsonObject("geo").get("latitude").isJsonNull());
			assertEquals("<script>plain</script>", event.getAsJsonObject("attributes").getAsJsonObject("fail2ban").get("jail").getAsString());
		}
	}
}
