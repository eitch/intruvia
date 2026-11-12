// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.stream;

import jakarta.websocket.Session;
import li.intruvia.core.model.EventJson;
import li.intruvia.core.model.SecurityEvent;
import li.intruvia.core.persistence.EventStreamNotifications;
import li.intruvia.core.read.*;
import li.intruvia.rest.session.ViewerSessions;
import li.intruvia.rest.session.ViewerException;
import li.strolch.agent.api.ComponentContainer;
import li.strolch.agent.api.StrolchComponent;
import li.strolch.service.api.ServiceHandler;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Strolch-owned durable dispatcher. Network sends never occur in ingestion's transaction or thread. */
public final class EventStreamComponent extends StrolchComponent {
	private final Set<StreamConnection> connections = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean pending = new AtomicBoolean();
	private ScheduledExecutorService scheduler;
	private ViewerSessions viewers;
	private StreamLimits limits = StreamLimits.DEFAULT;
	private final java.util.concurrent.atomic.AtomicLong queueDisconnects = new java.util.concurrent.atomic.AtomicLong();
	public long queueDisconnectCount() { return this.queueDisconnects.get(); }
	public StreamLimits limits() { return this.limits; }
	@Override public void initialize(li.strolch.runtime.configuration.ComponentConfiguration configuration) throws Exception {
		this.limits = StreamLimits.load(configuration);
		super.initialize(configuration);
	}
	public EventStreamComponent(ComponentContainer container, String name) { super(container, name); }
	public void configure(ViewerSessions viewers) { this.viewers = viewers; }
	public ViewerSessions viewers() { return this.viewers; }
	public int connectionCount() { return this.connections.size(); }

	@Override public void start() throws Exception {
		if (this.viewers == null) throw new IllegalStateException("Viewer validator required");
		super.start();
		this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> new Thread(task, "intruvia-event-stream"));
		this.scheduler.scheduleWithFixedDelay(this::dispatch, this.limits.pollMillis(), this.limits.pollMillis(), TimeUnit.MILLISECONDS);
		getComponent(EventStreamNotifications.class).listen(this::wakeup);
	}

	private void wakeup() {
		var worker = this.scheduler;
		if (worker == null || !this.pending.compareAndSet(false, true)) return;
		try {
			worker.execute(() -> { this.pending.set(false); dispatch(); });
		} catch (RejectedExecutionException e) { this.pending.set(false); }
	}

	StreamConnection connect(Session session, String token, long after) {
		var connection = new StreamConnection(session, token, after, connectionClosed -> {
			this.connections.remove(connectionClosed);
			if (connectionClosed.queueDisconnect())
				li.intruvia.rest.ops.RuntimeDiagnostics.increment(this.queueDisconnects);
		}, System::nanoTime, this.limits);
		this.connections.add(connection);
		wakeup();
		return connection;
	}

	private void dispatch() {
		for (var connection : this.connections) {
			try { advance(connection); }
			catch (RuntimeException e) {
				// Scheduled jobs must survive transient failures; no credential/model/exception details in frames or logs.
				connection.finish(frame("error", "events_unavailable"), 1011);
			}
		}
	}

	private void advance(StreamConnection connection) {
		connection.heartbeat();
		if (!connection.active()) return;
		li.strolch.privilege.model.Certificate certificate;
		try { certificate = this.viewers.validateToken(connection.token); }
		catch (ViewerException e) { connection.close(1008, "Viewer session ended"); return; }
		var services = getComponent(ServiceHandler.class);
		if (!connection.hello) {
			var result = services.doService(certificate, new EventReadService(), new EventReadArgument(true, 0, null, 1));
			if (!result.isOk()) { connection.finish(frame("error", "events_unavailable"), 1011); return; }
			var snapshot = (EventReadResult.Snapshot) result.body();
			connection.offer(EventJson.write(new Hello(1, "hello", snapshot.cursor(), snapshot.minAfter())));
			connection.hello = true;
		}
		var result = services.doService(certificate, new EventReadService(), new EventReadArgument(false, connection.after, null, 1000));
		switch (result.outcome()) {
			case EXPIRED, INVALID -> connection.finish(frame("resync_required", "resync_required"), 1008);
			case UNAVAILABLE -> connection.finish(frame("error", "events_unavailable"), 1011);
			case OK -> {
				var page = (EventReadResult.Page) result.body();
				for (var event : page.items()) {
					connection.offer(EventJson.write(new Created(1, "event.created", event.sequence(), event)));
					connection.after = Long.parseLong(event.sequence());
					if (!connection.active()) break;
				}
				if (page.hasMore() && connection.active()) wakeup();
			}
		}
	}

	private static String frame(String type, String code) { return EventJson.write(new Notice(1, type, code)); }
	private record Hello(int version, String type, String head, String minAfter) {}
	private record Created(int version, String type, String sequence, SecurityEvent event) {}
	private record Notice(int version, String type, String code) {}

	public void quiesce() throws InterruptedException {
		getComponent(EventStreamNotifications.class).listen(() -> {});
		var worker = this.scheduler;
		if (worker == null) return;
		worker.shutdownNow();
		if (!worker.awaitTermination(30, TimeUnit.SECONDS)) throw new IllegalStateException("Stream dispatcher did not stop");
		for (var connection : this.connections) connection.close(1001, "Server shutdown");
		this.scheduler = null;
	}
	@Override public void stop() throws Exception { quiesce(); super.stop(); }
	@Override public void destroy() throws Exception { quiesce(); super.destroy(); }
}
