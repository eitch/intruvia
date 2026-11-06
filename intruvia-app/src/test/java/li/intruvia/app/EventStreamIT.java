// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import com.google.gson.*;
import li.intruvia.core.ingest.*;
import li.intruvia.core.model.*;
import li.intruvia.core.persistence.*;
import li.intruvia.core.auth.MachineIdentities;
import li.intruvia.rest.stream.EventStreamComponent;
import li.strolch.service.api.ServiceHandler;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static li.intruvia.core.model.ModelConstants.*;
import static org.junit.Assert.*;

public class EventStreamIT {
	private static final String ORIGIN = "https://intruvia.example.org";

	@Test(timeout = 60000)
	public void snapshotGapReplayLivePollingAndRestart() throws Exception {
		try (var database = ViewerSessionIT.database(ORIGIN)) {
			try (var client = HttpClient.newHttpClient(); var app = new IntruviaApplication(database.runtime, 0)) {
				app.start();
				String cookie = login(client, app, "viewer");
				assertEquals("0", snapshot(client, app, cookie).get("cursor").getAsString());
				// Commit in the snapshot/upgrade gap; bypass notifier to reproduce commit-before-wakeup crash window.
				seed(app, 1, 3, 0);
				var listener = new Listener();
				var socket = connect(client, app, cookie, "0", listener);
				assertHello(listener, "3", "0");
				for (int i = 1; i <= 3; i++) assertEvent(listener, i);
				seed(app, 4, 4, 0);
				assertEvent(listener, 4); // periodic catch-up, no notification
				var handler = app.agent().getPrivilegeHandler().getPrivilegeHandler();
				var owner = handler.authenticate("denied", "viewer-test-password".toCharArray(), false);
				var services = app.agent().getContainer().getComponent(ServiceHandler.class);
				var identities = new MachineIdentities(Map.of("denied", "server-a"));
				var payload = new Fail2banEvent(UUID.randomUUID(), Instant.now(), "192.0.2.1", "sshd", 1);
				var accepted = services.doService(owner, new IngestService(identities), new IngestArgument(payload));
				assertEquals(IngestResult.Outcome.CREATED, accepted.outcome());
				assertEvent(listener, 5);
				var duplicate = services.doService(owner, new IngestService(identities), new IngestArgument(payload));
				assertEquals(IngestResult.Outcome.DUPLICATE, duplicate.outcome());
				assertNull(listener.frames.poll(400, TimeUnit.MILLISECONDS));
				var failing = new Fail2banEvent(UUID.randomUUID(), Instant.now(), "192.0.2.1", "sshd", 1);
				database.rejectReceipt(IngestCommand.receiptId("server-a", failing.eventId()));
				var rejected = services.doService(owner, new IngestService(identities), new IngestArgument(failing));
				assertEquals(IngestResult.Outcome.UNAVAILABLE, rejected.outcome());
				assertNull(listener.frames.poll(400, TimeUnit.MILLISECONDS));
				database.allowReceipt();
				socket.sendText("{\"type\":\"delete\"}", true).join();
				assertEquals(1008, (int) listener.closed.get(5, TimeUnit.SECONDS));
				assertEquals("5", snapshot(client, app, cookie).get("cursor").getAsString());
				socket.abort();
			}
			try (var client = HttpClient.newHttpClient(); var app = new IntruviaApplication(database.runtime, 0)) {
				app.start();
				var listener = new Listener();
				var socket = connect(client, app, login(client, app, "viewer"), "4", listener);
				assertHello(listener, "5", "0");
				assertEvent(listener, 5);
				socket.abort();
			}
		}
	}

	@Test(timeout = 60000)
	public void originCookiePrivilegeAndPatBoundaryBeforeUpgrade() throws Exception {
		try (var database = ViewerSessionIT.database(ORIGIN); var client = HttpClient.newHttpClient();
				var app = new IntruviaApplication(database.runtime, 0)) {
			app.start();
			String cookie = login(client, app, "viewer");
			var ordinary = HttpRequest.newBuilder(URI.create(base(app) + "/events/stream?after=0"))
					.header("Cookie", cookie).header("Origin", ORIGIN).build();
			var ordinaryResponse = client.send(ordinary, HttpResponse.BodyHandlers.ofString());
			assertEquals(400, ordinaryResponse.statusCode());
			assertFalse(ordinaryResponse.body().contains("Exception"));
			rejected(client, app, Map.of("Cookie", cookie), "0", 403);
			for (String origin : List.of("null", "https://evil.example", ORIGIN + "/", ORIGIN + ".evil"))
				rejected(client, app, Map.of("Cookie", cookie, "Origin", origin), "0", 403);
			rejected(client, app, Map.of("Origin", ORIGIN), "0", 401);
			rejected(client, app, Map.of("Origin", ORIGIN, "Cookie", cookie + "; " + cookie), "0", 401);
			var handler = app.agent().getPrivilegeHandler().getPrivilegeHandler();
			var denied = handler.authenticate("denied", "viewer-test-password".toCharArray(), "intruvia-viewer",
					li.strolch.privilege.model.Usage.ANY, false);
			rejected(client, app, Map.of("Origin", ORIGIN, "Cookie", "IntruviaSession=" + denied.getAuthToken()), "0", 403);
			var owner = handler.authenticate("issuer", "viewer-test-password".toCharArray(), false);
			String pat = handler.createPersonalAccessToken(owner, "socket-test", ZonedDateTime.now().minusMinutes(1),
					ZonedDateTime.now().plusMinutes(5), Set.of(), Set.of("event:read"));
			var patCertificate = handler.authenticatePersonalAccessToken(pat, "intruvia-viewer");
			for (String token : List.of(pat, patCertificate.getAuthToken())) {
				rejected(client, app, Map.of("Origin", ORIGIN, "Authorization", "Bearer " + token), "0", 401);
				rejected(client, app, Map.of("Origin", ORIGIN, "Cookie", "IntruviaSession=" + token), "0", 401);
				rejected(client, app, Map.of("Origin", ORIGIN, "Cookie", cookie, "Authorization", "Bearer " + token), "0", 401);
			}
			for (String after : List.of("", "-1", "01", "9223372036854775808", "0&after=0", "0&token=secret"))
				rejected(client, app, Map.of("Origin", ORIGIN, "Cookie", cookie), after, 400);
			var listener = new Listener();
			var socket = connect(client, app, cookie, "0", listener);
			assertHello(listener, "0", "0");
			var logout = HttpRequest.newBuilder(URI.create(base(app) + "/session")).header("Origin", ORIGIN)
					.header("Cookie", cookie).header("X-Intruvia-CSRF", "1").DELETE().build();
			assertEquals(204, client.send(logout, HttpResponse.BodyHandlers.ofString()).statusCode());
			assertEquals(1008, (int) listener.closed.get(5, TimeUnit.SECONDS));
			rejected(client, app, Map.of("Origin", ORIGIN, "Cookie", cookie), "0", 401);
			socket.abort();
		}
	}

	@Test(timeout = 60000)
	public void expiryClosesAndRejectsUpgrade() throws Exception {
		var now = new AtomicReference<>(Instant.now());
		Clock clock = new Clock() {
			@Override public ZoneId getZone() { return ZoneOffset.UTC; }
			@Override public Clock withZone(ZoneId zone) { return this; }
			@Override public Instant instant() { return now.get(); }
		};
		try (var database = ViewerSessionIT.database(ORIGIN); var client = HttpClient.newHttpClient();
				var app = new IntruviaApplication(database.runtime, 0, clock)) {
			app.start();
			String cookie = login(client, app, "viewer");
			var listener = new Listener();
			var socket = connect(client, app, cookie, "0", listener);
			assertHello(listener, "0", "0");
			now.set(now.get().plusSeconds(1801));
			assertEquals(1008, (int) listener.closed.get(5, TimeUnit.SECONDS));
			rejected(client, app, Map.of("Origin", ORIGIN, "Cookie", cookie), "0", 401);
			socket.abort();
		}
	}

	@Test(timeout = 60000)
	public void retentionAndAheadCursorsExplicitlyResync() throws Exception {
		try (var database = ViewerSessionIT.database(ORIGIN); var client = HttpClient.newHttpClient();
				var app = new IntruviaApplication(database.runtime, 0)) {
			app.start();
			String cookie = login(client, app, "viewer");
			seed(app, 1, 3, 0);
			prune(app, 2);
			for (String after : List.of("0", "4")) {
				var listener = new Listener();
				var socket = connect(client, app, cookie, after, listener);
				assertHello(listener, "3", "2");
				assertEquals("resync_required", listener.next().get("type").getAsString());
				assertEquals(1008, (int) listener.closed.get(5, TimeUnit.SECONDS));
				socket.abort();
			}
			var listener = new Listener();
			var socket = connect(client, app, cookie, "2", listener);
			assertHello(listener, "3", "2");
			assertEvent(listener, 3);
			// Remove the unread range in the production lock; tailer must resync instead of skipping to 6.
			app.agent().getPrivilegeHandler().runAsAgent(ctx -> {
				try (var tx = app.agent().openTx(ctx.getCertificate(), "AtomicPruningFixture", false).rollbackOnFailure()) {
					EventStreamLock.acquire(tx);
					var repository = new EventRepository();
					tx.streamResources(TYPE_SECURITY_EVENT).toList().forEach(tx::remove);
					repository.updateStreamState(tx, new EventStreamState("6", "5"));
					var payload = new Fail2banEvent(UUID.randomUUID(), Instant.now(), "192.0.2.1", "sshd", null);
					repository.addEvent(tx, payload.toEvent("server-a", UUID.randomUUID(), 6, Instant.now(),
							new SecurityEvent.Geo(GeoStatus.UNAVAILABLE, null, null, null, null, null, null, null, null, null, null, null)));
					tx.commitOnClose();
				}
			});
			JsonObject frame;
			do { frame = listener.next(); } while (frame.get("type").getAsString().equals("event.created"));
			assertEquals("resync_required", frame.get("type").getAsString());
			socket.abort();
		}
	}

	@Test(timeout = 60000)
	public void blockedTransportOverflowsWhileIngestionContinues() throws Exception {
		try (var database = ViewerSessionIT.database(ORIGIN); var client = HttpClient.newHttpClient();
				var app = new IntruviaApplication(database.runtime, 0)) {
			app.start();
			String cookie = login(client, app, "viewer");
			var closed = new CompletableFuture<Integer>();
			// Deterministic transport fault: accept sends but never complete them, independent of OS socket buffers.
			var remote = (jakarta.websocket.RemoteEndpoint.Async) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[]{jakarta.websocket.RemoteEndpoint.Async.class}, (proxy, method, args) -> null);
			var session = (jakarta.websocket.Session) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[]{jakarta.websocket.Session.class}, (proxy, method, args) -> {
				if (method.getName().equals("getAsyncRemote")) return remote;
				if (method.getName().equals("close")) closed.complete(((jakarta.websocket.CloseReason) args[0]).getCloseCode().getCode());
				return null;
			});
			seed(app, 1, 1001, 0);
			var streams = app.agent().getContainer().getComponent(EventStreamComponent.class);
			var connect = EventStreamComponent.class.getDeclaredMethod("connect", jakarta.websocket.Session.class, String.class, long.class);
			connect.setAccessible(true);
			connect.invoke(streams, session, cookie.substring(cookie.indexOf('=') + 1), 0L);
			var owner = app.agent().getPrivilegeHandler().getPrivilegeHandler()
					.authenticate("denied", "viewer-test-password".toCharArray(), false);
			var result = app.agent().getContainer().getComponent(ServiceHandler.class).doService(owner,
					new IngestService(new MachineIdentities(Map.of("denied", "server-a"))),
					new IngestArgument(new Fail2banEvent(UUID.randomUUID(), Instant.now(), "192.0.2.1", "sshd", 1)));
			assertEquals(IngestResult.Outcome.CREATED, result.outcome());
			assertEquals(1013, (int) closed.get(5, TimeUnit.SECONDS));
			assertEquals(0, streams.connectionCount());
			assertEquals("1002", snapshot(client, app, cookie).get("cursor").getAsString());
		}
	}

	@Test(timeout = 60000)
	public void fullWidthSequencesAndShutdown() throws Exception {
		try (var database = ViewerSessionIT.database(ORIGIN); var client = HttpClient.newHttpClient();
				var app = new IntruviaApplication(database.runtime, 0)) {
			app.start();
			long start = 9007199254740992L;
			seed(app, start + 1, start + 2, start);
			var listener = new Listener();
			var socket = connect(client, app, login(client, app, "viewer"), Long.toString(start), listener);
			assertHello(listener, Long.toString(start + 2), Long.toString(start));
			assertEvent(listener, start + 1);
			assertEvent(listener, start + 2);
			var streams = app.agent().getContainer().getComponent(EventStreamComponent.class);
			app.close();
			assertEquals(1001, (int) listener.closed.get(5, TimeUnit.SECONDS));
			assertEquals(0, streams.connectionCount());
			assertFalse(Thread.getAllStackTraces().keySet().stream()
					.anyMatch(thread -> thread.isAlive() && thread.getName().equals("intruvia-event-stream")));
			socket.abort();
		}
	}

	@Test(timeout = 60000)
	public void upgradesShareUnauthenticatedRestAdmissionLimit() throws Exception {
		try (var database = ViewerSessionIT.database(ORIGIN); var client = HttpClient.newHttpClient()) {
			java.nio.file.Files.writeString(database.runtime.resolve("config/ingestion.properties"), "globalPerSecond=1\nglobalBurst=1\n");
			try (var app = new IntruviaApplication(database.runtime, 0)) {
				app.start();
				var request = HttpRequest.newBuilder(URI.create(base(app) + "/session")).build();
				assertEquals(401, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
				rejected(client, app, Map.of("Origin", ORIGIN), "0", 429);
				var limited = client.send(request, HttpResponse.BodyHandlers.ofString());
				assertEquals(429, limited.statusCode());
				assertEquals("1", limited.headers().firstValue("Retry-After").orElseThrow());
			}
		}
	}

	private static void assertHello(Listener listener, String head, String minimum) throws Exception {
		var frame = listener.next();
		assertEquals(1, frame.get("version").getAsInt());
		assertEquals("hello", frame.get("type").getAsString());
		assertTrue(frame.get("head").getAsJsonPrimitive().isString());
		assertEquals(head, frame.get("head").getAsString());
		assertEquals(minimum, frame.get("minAfter").getAsString());
	}
	private static void assertEvent(Listener listener, long sequence) throws Exception {
		var frame = listener.next();
		assertEquals(1, frame.get("version").getAsInt());
		assertEquals("event.created", frame.get("type").getAsString());
		assertTrue(frame.get("sequence").getAsJsonPrimitive().isString());
		assertEquals(Long.toString(sequence), frame.get("sequence").getAsString());
		var event = frame.getAsJsonObject("event");
		assertEquals(frame.get("sequence"), event.get("sequence"));
		assertEquals(Set.of("schemaVersion", "id", "sequence", "source", "category", "action", "occurredAt", "receivedAt",
				"subject", "observer", "attributes", "geo"), event.keySet());
	}
	private static String base(IntruviaApplication app) { return "http://127.0.0.1:" + app.port() + "/api/v1"; }
	private static WebSocket connect(HttpClient client, IntruviaApplication app, String cookie, String after, Listener listener) throws Exception {
		return client.newWebSocketBuilder().header("Origin", ORIGIN).header("Cookie", cookie).connectTimeout(Duration.ofSeconds(5))
				.buildAsync(URI.create(base(app).replace("http:", "ws:") + "/events/stream?after=" + after), listener).get(5, TimeUnit.SECONDS);
	}
	private static void rejected(HttpClient client, IntruviaApplication app, Map<String, String> headers, String after, int status) {
		var builder = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5));
		headers.forEach(builder::header);
		var error = assertThrows(ExecutionException.class, () -> builder.buildAsync(
				URI.create(base(app).replace("http:", "ws:") + "/events/stream?after=" + after), new Listener()).get(5, TimeUnit.SECONDS));
		assertTrue(error.getCause().toString(), error.getCause() instanceof WebSocketHandshakeException);
		assertEquals(status, ((WebSocketHandshakeException) error.getCause()).getResponse().statusCode());
	}
	private static String login(HttpClient client, IntruviaApplication app, String username) throws Exception {
		var request = HttpRequest.newBuilder(URI.create(base(app) + "/session")).header("Origin", ORIGIN)
				.header("X-Intruvia-CSRF", "1").header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + username + "\",\"password\":\"viewer-test-password\"}")).build();
		var response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals(response.body(), 200, response.statusCode());
		return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
	}
	private static JsonObject snapshot(HttpClient client, IntruviaApplication app, String cookie) throws Exception {
		var request = HttpRequest.newBuilder(URI.create(base(app) + "/events/snapshot")).header("Cookie", cookie).build();
		var response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals(response.body(), 200, response.statusCode());
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}
	private static final class Listener implements WebSocket.Listener {
		final BlockingQueue<JsonObject> frames = new LinkedBlockingQueue<>();
		final CompletableFuture<Integer> closed = new CompletableFuture<>();
		final StringBuilder fragments = new StringBuilder();
		@Override public void onOpen(WebSocket socket) { socket.request(1); }
		@Override public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
			this.fragments.append(text);
			if (last) { this.frames.add(JsonParser.parseString(this.fragments.toString()).getAsJsonObject()); this.fragments.setLength(0); }
			socket.request(1); return null;
		}
		@Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) { this.closed.complete(code); return null; }
		@Override public void onError(WebSocket socket, Throwable error) { this.closed.completeExceptionally(error); }
		JsonObject next() throws Exception {
			var frame = this.frames.poll(5, TimeUnit.SECONDS);
			assertNotNull("Expected stream frame", frame); return frame;
		}
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
}
