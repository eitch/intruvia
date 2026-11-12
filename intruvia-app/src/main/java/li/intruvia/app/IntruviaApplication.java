// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import li.intruvia.core.auth.MachineIdentities;
import li.intruvia.rest.stream.EventStreamComponent;
import li.intruvia.rest.stream.EventStreamServlet;
import li.intruvia.rest.ingest.TokenBucket;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import li.intruvia.rest.session.ViewerConfiguration;
import li.intruvia.rest.session.ViewerSessions;
import li.strolch.runtime.sessions.StrolchSessionHandler;
import java.time.Clock;
import li.intruvia.rest.IntruviaRestApplication;
import li.intruvia.rest.ingest.IngestionLimits;
import li.strolch.service.api.ServiceHandler;
import org.eclipse.jetty.server.ConnectionLimit;
import li.strolch.agent.api.ComponentState;
import li.strolch.agent.api.StrolchAgent;
import li.strolch.agent.api.StrolchBootstrapper;
import li.strolch.agent.api.StrolchVersion;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

/** Owns the HTTP and Strolch lifecycles; only loopback is exposed by the skeleton. */
public final class IntruviaApplication implements AutoCloseable {
	private static final Logger logger = LoggerFactory.getLogger(IntruviaApplication.class);
	private final StrolchAgent agent;
	private final Server server;
	private final ViewerSessions viewers;
	private final ServerConnector connector;
	private boolean closed;
	private final java.util.concurrent.Semaphore readinessProbe = new java.util.concurrent.Semaphore(1);
	private final li.intruvia.rest.ops.RuntimeDiagnostics diagnostics = new li.intruvia.rest.ops.RuntimeDiagnostics();
	private final RequestLifecycle requests = new RequestLifecycle(this.diagnostics);

	public IntruviaApplication(Path runtime, int port) {
		this(runtime, port, Clock.systemUTC());
	}

	IntruviaApplication(Path runtime, int port, Clock clock) {
		if (port < 0 || port > 65535)
			throw new IllegalArgumentException("Port must be between 0 and 65535");
		java.util.logging.LogManager.getLogManager().reset();
		java.util.logging.Logger.getLogger("").setLevel(java.util.logging.Level.OFF);
		RuntimeConfiguration.validate(runtime);
		MachineIdentities identities;
		IngestionLimits limits;
		ViewerConfiguration viewer;
		Path identityFile = runtime.resolve("config/machine-identities.conf");
		try {
			identities = MachineIdentities.load(identityFile);
			viewer = ViewerConfiguration.load(runtime.resolve("config/viewer.properties"));
			limits = IngestionLimits.load(runtime.resolve("config/ingestion.properties"));
		} catch (IOException e) {
			throw new IllegalArgumentException("Cannot load external identity/viewer/ingestion configuration");
		}
		Properties version = new Properties();
		version.setProperty("groupId", "li.intruvia");
		version.setProperty("artifactId", "intruvia-app");
		version.setProperty("artifactVersion", "0.0.1");
		this.agent = new StrolchBootstrapper(new StrolchVersion(version)).setupByRoot("production", runtime.toFile());
		PersistenceConfiguration.validate(this.agent.getStrolchConfiguration());
		RuntimeConfiguration.validateSessions(this.agent.getStrolchConfiguration());
		this.server = new Server();
		this.server.setStopTimeout(5000);
		this.connector = new ServerConnector(this.server);
		this.connector.setHost("127.0.0.1");
		this.connector.setPort(port);
		this.connector.setIdleTimeout(limits.idleSeconds() * 1000L);
		this.server.addConnector(this.connector);
		this.server.addBean(new ConnectionLimit(limits.connections(), this.connector));
		ServletContextHandler context = new ServletContextHandler();
		context.setContextPath("/");
		context.addServlet(new ServletHolder(new StaticPageServlet()), "/");
		var health = new IntruviaRestApplication();
		health.register(new li.intruvia.rest.HealthResource(this::ready));
		context.addServlet(new ServletHolder(new ServletContainer(health)), "/health/*");
		context.addFilter(new org.eclipse.jetty.ee10.servlet.FilterHolder(this.requests), "/api/*",
				java.util.EnumSet.of(jakarta.servlet.DispatcherType.REQUEST));
		var admission = new TokenBucket(limits.globalPerSecond(), limits.globalBurst());
		var api = new IntruviaRestApplication(() -> this.agent.getPrivilegeHandler().getPrivilegeHandler(), identities,
				() -> this.agent.getContainer().getComponent(ServiceHandler.class), limits, admission);
		this.viewers = new ViewerSessions(() -> this.agent.getContainer().getComponent(StrolchSessionHandler.class), viewer, clock);
		api.viewers(this.viewers);
		api.register(new li.intruvia.rest.ops.DiagnosticsResource(this.viewers, this::diagnosticSnapshot));
		JakartaWebSocketServletContainerInitializer.configure(context,
				(servletContext, container) -> {});
		context.addServlet(new ServletHolder(new EventStreamServlet(
				() -> this.agent.getContainer().getComponent(EventStreamComponent.class), admission)), "/api/v1/events/stream");
		context.addServlet(new ServletHolder(new ServletContainer(api)), "/api/v1/*");
		this.server.setHandler(context);
	}

	public void start() throws Exception {
		this.agent.initialize();
		this.agent.getContainer().getComponent(EventStreamComponent.class).configure(this.viewers);
		this.agent.start();
		if (this.agent.getContainer().getState() != ComponentState.STARTED)
			throw new IllegalStateException("Strolch did not start");
		this.server.start();
		logger.info("Intruvia started: http://127.0.0.1:{} (Strolch STARTED)", port());
	}

	boolean ready() {
		if (this.requests.stopping() || this.agent.getContainer().getState() != ComponentState.STARTED) return false;
		if (!this.readinessProbe.tryAcquire()) return false;
		try (var connection = ((li.strolch.persistence.postgresql.PostgreSqlPersistenceHandler) this.agent.getContainer()
				.getComponent(li.strolch.persistence.api.PersistenceHandler.class)).getConnection("defaultRealm")) {
			return connection.isValid(2);
		} catch (java.sql.SQLException | RuntimeException e) {
			return false;
		} finally {
			this.readinessProbe.release();
		}
	}

	private Object diagnosticSnapshot() {
		var container = this.agent.getContainer();
		var geo = container.getComponent(li.intruvia.core.geo.GeoIpComponent.class).diagnostics();
		var stream = container.getComponent(EventStreamComponent.class);
		return new Diagnostics(this.diagnostics.counts(), stream.connectionCount(), stream.queueDisconnectCount(),
				new GeoDiagnostics(geo.available(), geo.databaseEdition(), string(geo.databaseBuildAt()),
						string(geo.lastSuccessfulLoadAt()), geo.databaseAge() == null ? null : geo.databaseAge().getSeconds(),
						geo.stale(), geo.updateFailed()), stream.limits());
	}
	private static String string(java.time.Instant value) { return value == null ? null : value.toString(); }
	private record Diagnostics(li.intruvia.rest.ops.RuntimeDiagnostics.Counts ingestion, int streamConnections,
			long queueDisconnects, GeoDiagnostics geoIp, li.intruvia.rest.stream.StreamLimits streamLimits) {}
	private record GeoDiagnostics(boolean available, String databaseEdition, String databaseBuildAt,
			String lastSuccessfulLoadAt, Long databaseAgeSeconds, boolean stale, boolean updateFailed) {}

	StrolchAgent agent() {
		return this.agent;
	}

	public int port() {
		return this.connector.getLocalPort();
	}

	@Override
	public synchronized void close() throws Exception {
		if (this.closed)
			return;
		this.requests.drain();
		this.agent.getContainer().getComponent(EventStreamComponent.class).quiesce();
		this.closed = true;
		var container = this.agent.getContainer();
		try {
			this.server.stop();
		} finally {
			try {
				this.server.destroy();
			} finally {
				try {
					this.agent.stop();
				} finally {
					this.agent.destroy();
				}
			}
		}
		if (!this.server.isStopped() || container.getState() != ComponentState.DESTROYED)
			throw new IllegalStateException("Application shutdown was incomplete");
		logger.info("Intruvia stopped: Jetty STOPPED; Strolch DESTROYED");
	}

	public static void main(String[] args) {
		try { run(args); }
		catch (Exception e) {
			throw new IllegalStateException(e instanceof ConfigurationFailure ? e.getMessage() :
					"Intruvia startup/runtime failed; check protected configuration and database availability");
		}
	}

	private static void run(String[] args) throws Exception {
		if (args.length < 1 || args.length > 2)
			throw new IllegalArgumentException("Usage: java -jar intruvia-app-0.0.1.jar <runtime-directory> [port; default 8080]");
		int port = args.length == 2 ? Integer.parseInt(args[1]) : 8080;
		try (IntruviaApplication application = new IntruviaApplication(Path.of(args[0]), port)) {
			Thread shutdown = new Thread(() -> {
				try {
					application.close();
				} catch (Exception e) {
					logger.error("Application shutdown failed");
				}
			}, "intruvia-shutdown");
			Runtime.getRuntime().addShutdownHook(shutdown);
			application.start();
			application.server.join();
		}
	}
}
