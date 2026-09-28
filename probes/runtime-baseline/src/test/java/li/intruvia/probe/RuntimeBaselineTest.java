// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.probe;

import jakarta.websocket.OnMessage;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import li.strolch.agent.api.ComponentState;
import li.strolch.agent.api.ComponentContainer;
import li.strolch.agent.api.StrolchAgent;
import li.strolch.agent.api.StrolchBootstrapper;
import li.strolch.agent.api.StrolchVersion;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class RuntimeBaselineTest {
	private static final Logger logger = LoggerFactory.getLogger(RuntimeBaselineTest.class);

	@Test(timeout = 60000)
	public void startsStrolchAndServesRestAndWebSocketThenStops() throws Exception {
		Properties properties = new Properties();
		properties.setProperty("groupId", "li.intruvia");
		properties.setProperty("artifactId", "runtime-baseline-probe");
		properties.setProperty("artifactVersion", "0.0.1");
		StrolchAgent agent = new StrolchBootstrapper(new StrolchVersion(properties)).setupByCopyingRoot("dev",
				new File("src/test/resources/runtime"), new File("target/RuntimeBaselineTest"));
		ComponentContainer strolchContainer = agent.getContainer();
		Server server = new Server();
		ServerConnector connector = new ServerConnector(server);
		connector.setHost("127.0.0.1");
		connector.setPort(0);
		server.addConnector(connector);
		ServletContextHandler context = new ServletContextHandler();
		context.setContextPath("/");
		server.setHandler(context);
		ResourceConfig resources = new ResourceConfig().property("jersey.config.server.wadl.disableWadl", true).register(ProbeResource.class);
		context.addServlet(new ServletHolder(new ServletContainer(resources)), "/rest/*");
		JakartaWebSocketServletContainerInitializer.configure(context,
				(servletContext, container) -> container.addEndpoint(ProbeSocket.class));
		try {
			agent.initialize();
			agent.start();
			assertEquals(ComponentState.STARTED, agent.getContainer().getState());
			server.start();
			assertTrue(server.isStarted());
			logger.info("PROBE Strolch STARTED; Jetty STARTED on loopback ephemeral port");
			try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
				URI rest = URI.create("http://127.0.0.1:" + connector.getLocalPort() + "/rest/probe");
				HttpResponse<String> response = client.send(HttpRequest.newBuilder(rest).timeout(Duration.ofSeconds(5)).build(),
						HttpResponse.BodyHandlers.ofString());
				assertEquals(200, response.statusCode());
				assertEquals("jakarta-rest-ok", response.body());
				logger.info("PROBE JAX-RS HTTP 200 and expected body");
				SocketListener listener = new SocketListener();
				WebSocket socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
						.buildAsync(URI.create("ws://127.0.0.1:" + connector.getLocalPort() + "/socket"), listener)
						.get(5, TimeUnit.SECONDS);
				try {
					socket.sendText("baseline", true).get(5, TimeUnit.SECONDS);
					assertEquals("jakarta-websocket:baseline", listener.message.get(5, TimeUnit.SECONDS));
					socket.sendClose(WebSocket.NORMAL_CLOSURE, "probe complete").get(5, TimeUnit.SECONDS);
					assertEquals(WebSocket.NORMAL_CLOSURE, (int) listener.closed.get(5, TimeUnit.SECONDS));
					logger.info("PROBE WebSocket upgrade, text exchange and normal close passed");
				} finally {
					socket.abort();
				}
			}
		} finally {
			try {
				server.stop();
				assertTrue(server.isStopped());
				server.destroy();
			} finally {
				try {
					agent.stop();
					assertEquals(ComponentState.STOPPED, agent.getContainer().getState());
				} finally {
					agent.destroy();
				}
			}
		}
		assertEquals(ComponentState.DESTROYED, strolchContainer.getState());
		logger.info("PROBE Jetty STOPPED and Strolch DESTROYED");
	}

	@Test
	public void usesJakartaApisAndLoadsPostgresqlDriver() throws Exception {
		for (Class<?> api : new Class<?>[]{jakarta.ws.rs.core.Application.class, jakarta.servlet.Servlet.class,
				jakarta.websocket.Session.class, jakarta.websocket.server.ServerContainer.class}) {
			assertTrue(api.getName().startsWith("jakarta."));
			logger.info("PROBE API {} from {}", api.getName(), api.getProtectionDomain().getCodeSource().getLocation());
		}
		for (String legacy : new String[]{"javax.ws.rs.core.Application", "javax.servlet.Servlet", "javax.websocket.Session"}) {
			assertThrows(ClassNotFoundException.class, () -> Class.forName(legacy));
		}
		assertTrue(new org.postgresql.Driver().acceptsURL("jdbc:postgresql://localhost/probe"));
		assertNotNull(Class.forName("li.strolch.persistence.postgresql.PostgreSqlPersistenceHandler"));
		assertNotNull(Class.forName("li.strolch.rest.filters.AuthenticationRequestFilter"));
		logger.info("PROBE no legacy web APIs; Strolch REST/persistence classes and PostgreSQL driver load");
	}

	@Path("/probe")
	public static class ProbeResource {
		@GET
		@Produces(MediaType.TEXT_PLAIN)
		public String get() {
			return "jakarta-rest-ok";
		}
	}

	@ServerEndpoint("/socket")
	public static class ProbeSocket {
		@OnMessage
		public String echo(String value) {
			return "jakarta-websocket:" + value;
		}
	}

	private static class SocketListener implements WebSocket.Listener {
		private final CompletableFuture<String> message = new CompletableFuture<>();
		private final CompletableFuture<Integer> closed = new CompletableFuture<>();
		private final StringBuilder fragments = new StringBuilder();

		@Override
		public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
			this.fragments.append(data);
			if (last)
				this.message.complete(this.fragments.toString());
			socket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
			this.closed.complete(status);
			return null;
		}

		@Override
		public void onError(WebSocket socket, Throwable error) {
			this.message.completeExceptionally(error);
			this.closed.completeExceptionally(error);
		}
	}
}
