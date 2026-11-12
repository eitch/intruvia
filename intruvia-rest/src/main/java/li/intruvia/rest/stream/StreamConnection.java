// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.stream;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.function.Consumer;

/** One outstanding asynchronous send, with a bounded pending queue and independent send deadline. */
final class StreamConnection {
	static final int CAPACITY = 1000;
	static final int MESSAGE_BYTES = 65536;
	private final Session session;
	private final StreamLimits limits;
	private final java.util.function.LongSupplier time;
	private final ArrayDeque<String> queue = new ArrayDeque<>();
	private final Consumer<StreamConnection> removal;
	private boolean sending;
	private boolean closed;
	private boolean queueDisconnect;
	synchronized boolean queueDisconnect() { return this.queueDisconnect; }
	private int terminalCode;
	private long sendStarted;
	private long pingAt;
	private boolean awaitingPong;
	final String token;
	long after;
	boolean hello;

	StreamConnection(Session session, String token, long after, Consumer<StreamConnection> removal) {
		this(session, token, after, removal, System::nanoTime);
	}

	StreamConnection(Session session, String token, long after, Consumer<StreamConnection> removal, java.util.function.LongSupplier time) {
		this(session, token, after, removal, time, StreamLimits.DEFAULT);
	}

	StreamConnection(Session session, String token, long after, Consumer<StreamConnection> removal,
			java.util.function.LongSupplier time, StreamLimits limits) {
		this.session = session;
		this.limits = limits;
		this.time = time;
		this.token = token;
		this.after = after;
		this.removal = removal;
		this.pingAt = this.time.getAsLong();
		session.getAsyncRemote().setSendTimeout(limits.sendSeconds() * 1000L);
		session.setMaxTextMessageBufferSize(limits.messageBytes());
		session.setMaxBinaryMessageBufferSize(limits.messageBytes());
		session.setMaxIdleTimeout(45000);
	}

	synchronized void offer(String frame) {
		if (this.closed || this.terminalCode != 0)
			return;
		if (frame.getBytes(StandardCharsets.UTF_8).length > this.limits.messageBytes()) {
			close(1009, "Message limit");
			return;
		}
		if (this.queue.size() + (this.sending ? 1 : 0) >= this.limits.queueFrames()) {
			close(1013, "Slow consumer; reconnect");
			return;
		}
		this.queue.add(frame);
		drain();
	}

	synchronized void finish(String frame, int code) {
		offer(frame);
		this.terminalCode = code;
		if (!this.sending && this.queue.isEmpty()) close(code, "Reload snapshot");
	}

	private void drain() {
		if (this.sending || this.closed || this.queue.isEmpty()) return;
		this.sending = true;
		this.sendStarted = this.time.getAsLong();
		try {
			this.session.getAsyncRemote().sendText(this.queue.remove(), result -> {
				synchronized (this) {
					this.sending = false;
					if (!result.isOK()) close(1011, "Send failed");
					else if (this.queue.isEmpty() && this.terminalCode != 0) close(this.terminalCode, "Reload snapshot");
					else drain();
				}
			});
		} catch (RuntimeException e) {
			close(1011, "Send failed");
		}
	}

	synchronized boolean active() { return !this.closed && this.terminalCode == 0; }
	synchronized void pong() { this.awaitingPong = false; }

	synchronized void heartbeat() {
		long now = this.time.getAsLong();
		if (this.sending && now - this.sendStarted >= this.limits.sendSeconds() * 1_000_000_000L) {
			close(1013, "Send timeout; reconnect");
			return;
		}
		if (now - this.pingAt < 15_000_000_000L) return;
		if (this.awaitingPong) {
			close(1001, "Heartbeat timeout");
			return;
		}
		this.pingAt = now;
		this.awaitingPong = true;
		try { this.session.getAsyncRemote().sendPing(ByteBuffer.allocate(0)); }
		catch (IOException | RuntimeException e) { close(1011, "Heartbeat failed"); }
	}

	synchronized void close(int code, String reason) {
		if (this.closed) return;
		this.closed = true;
		this.queueDisconnect = code == 1013;
		this.queue.clear();
		this.removal.accept(this);
		try { this.session.close(new CloseReason(CloseReason.CloseCodes.getCloseCode(code), reason)); }
		catch (IOException | RuntimeException e) {
			// Already removed; Jetty's idle/send deadlines bound transport cleanup.
		}
	}
}
