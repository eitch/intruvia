// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.stream;

import jakarta.websocket.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class StreamConnectionTest {
	@Test(timeout = 2000)
	public void blockedSendHasBoundedQueueAndDisconnectsWithoutWaitingForNetwork() {
		var transport = new Transport();
		var connection = transport.connection();
		for (int i = 0; i < 1000; i++) connection.offer("frame-" + i);
		assertEquals(List.of("frame-0"), transport.sent);
		assertTrue(connection.active());
		connection.offer("overflow");
		assertFalse(connection.active());
		assertEquals(1013, transport.close.getCloseCode().getCode());
		assertEquals(1, transport.removed.get());
		// Late callbacks and close notifications cannot resurrect a closed connection or deliver queued frames.
		transport.complete();
		connection.close(1000, "Again");
		assertEquals(1, transport.sent.size());
		assertEquals(1, transport.removed.get());
	}

	@Test public void serializedSendsAndTerminalFrameStayOrdered() {
		var transport = new Transport();
		var connection = transport.connection();
		connection.offer("hello"); connection.offer("event-1"); connection.finish("resync", 1008);
		connection.offer("ignored");
		assertEquals(List.of("hello"), transport.sent);
		transport.complete(); assertEquals(List.of("hello", "event-1"), transport.sent);
		transport.complete(); assertEquals(List.of("hello", "event-1", "resync"), transport.sent);
		assertNull(transport.close);
		transport.complete(); assertEquals(1008, transport.close.getCloseCode().getCode());
	}

	@Test public void utf8MessageLimitAndSendDeadlineCloseConnection() {
		var large = new Transport();
		large.connection().offer("é".repeat(32769));
		assertEquals(1009, large.close.getCloseCode().getCode());
		assertTrue(large.sent.isEmpty());
		var blocked = new Transport();
		var connection = blocked.connection();
		connection.offer("pending");
		blocked.time.set(10_000_000_000L);
		connection.heartbeat();
		assertEquals(1013, blocked.close.getCloseCode().getCode());
	}

	@Test public void pingPongAndMissingPongDeadline() {
		var transport = new Transport();
		var connection = transport.connection();
		transport.time.set(15_000_000_000L); connection.heartbeat();
		assertEquals(1, transport.pings);
		connection.pong();
		transport.time.set(30_000_000_000L); connection.heartbeat();
		assertEquals(2, transport.pings);
		transport.time.set(45_000_000_000L); connection.heartbeat();
		assertEquals(1001, transport.close.getCloseCode().getCode());
	}

	@Test public void configuredQueueSizeAndDeadlineApplyToTransport() {
		var transport = new Transport();
		var connection = new StreamConnection(transport.session, "token", 0, ignored -> transport.removed.incrementAndGet(),
				transport.time::get, new StreamLimits(1, 1024, 1, 250));
		connection.offer("first");
		connection.offer("second");
		assertEquals(1013, transport.close.getCloseCode().getCode());
		assertTrue(connection.queueDisconnect());
		assertEquals(1, transport.removed.get());
		var deadline = new Transport();
		var waiting = new StreamConnection(deadline.session, "token", 0, ignored -> deadline.removed.incrementAndGet(),
				deadline.time::get, new StreamLimits(1, 1024, 1, 250));
		waiting.offer("first");
		deadline.time.set(1_000_000_000L);
		waiting.heartbeat();
		assertEquals(1013, deadline.close.getCloseCode().getCode());
		assertTrue(waiting.queueDisconnect());
	}

	private static final class Transport {
		final List<String> sent = new ArrayList<>();
		final AtomicLong time = new AtomicLong();
		final AtomicInteger removed = new AtomicInteger();
		SendHandler callback;
		CloseReason close;
		int pings;
		final RemoteEndpoint.Async remote = (RemoteEndpoint.Async) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[]{RemoteEndpoint.Async.class}, (proxy, method, args) -> {
			if (method.getName().equals("sendText")) { this.sent.add((String) args[0]); this.callback = (SendHandler) args[1]; }
			if (method.getName().equals("sendPing")) this.pings++;
			return null;
		});
		final Session session = (Session) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Session.class}, (proxy, method, args) -> {
			if (method.getName().equals("getAsyncRemote")) return this.remote;
			if (method.getName().equals("close")) this.close = (CloseReason) args[0];
			return null;
		});
		StreamConnection connection() { return new StreamConnection(this.session, "token", 0, ignored -> this.removed.incrementAndGet(), this.time::get); }
		void complete() { var saved = this.callback; this.callback = null; saved.onResult(new SendResult()); }
	}
}
