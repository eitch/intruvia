// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import li.intruvia.rest.IntruviaRestApplication;
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

import java.nio.file.Path;
import java.util.Properties;

/** Owns the HTTP and Strolch lifecycles; only loopback is exposed by the skeleton. */
public final class IntruviaApplication implements AutoCloseable {
	private static final Logger logger = LoggerFactory.getLogger(IntruviaApplication.class);
	private final StrolchAgent agent;
	private final Server server;
	private final ServerConnector connector;
	private boolean closed;

	public IntruviaApplication(Path runtime, int port) {
		if (port < 0 || port > 65535)
			throw new IllegalArgumentException("Port must be between 0 and 65535");
		Properties version = new Properties();
		version.setProperty("groupId", "li.intruvia");
		version.setProperty("artifactId", "intruvia-app");
		version.setProperty("artifactVersion", "0.0.1");
		this.agent = new StrolchBootstrapper(new StrolchVersion(version)).setupByRoot("skeleton", runtime.toFile());
		this.server = new Server();
		this.server.setStopTimeout(5000);
		this.connector = new ServerConnector(this.server);
		this.connector.setHost("127.0.0.1");
		this.connector.setPort(port);
		this.server.addConnector(this.connector);
		ServletContextHandler context = new ServletContextHandler();
		context.setContextPath("/");
		context.addServlet(new ServletHolder(new StaticPageServlet()), "/");
		context.addServlet(new ServletHolder(new ServletContainer(new IntruviaRestApplication())), "/health/*");
		this.server.setHandler(context);
	}

	public void start() throws Exception {
		this.agent.initialize();
		this.agent.start();
		if (this.agent.getContainer().getState() != ComponentState.STARTED)
			throw new IllegalStateException("Strolch did not start");
		this.server.start();
		logger.info("Intruvia started: http://127.0.0.1:{} (Strolch STARTED)", port());
	}

	public int port() {
		return this.connector.getLocalPort();
	}

	@Override
	public synchronized void close() throws Exception {
		if (this.closed)
			return;
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

	public static void main(String[] args) throws Exception {
		if (args.length < 1 || args.length > 2)
			throw new IllegalArgumentException("Usage: java -jar intruvia-app-0.0.1.jar <runtime-directory> [port; default 8080]");
		int port = args.length == 2 ? Integer.parseInt(args[1]) : 8080;
		try (IntruviaApplication application = new IntruviaApplication(Path.of(args[0]), port)) {
			Thread shutdown = new Thread(() -> {
				try {
					application.close();
				} catch (Exception e) {
					logger.error("Application shutdown failed", e);
				}
			}, "intruvia-shutdown");
			Runtime.getRuntime().addShutdownHook(shutdown);
			application.start();
			application.server.join();
		}
	}
}
