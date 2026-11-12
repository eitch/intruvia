// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.stream;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import li.intruvia.core.model.EventJson;
import li.intruvia.rest.dto.ApiError;
import li.intruvia.rest.session.ViewerException;
import li.intruvia.rest.session.ViewerSessions;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Supplier;

/** Validate before upgrade, with a fresh endpoint/configuration per request; no shared handshake credentials. */
public final class EventStreamServlet extends HttpServlet {
	private final Supplier<EventStreamComponent> streams;
	private final li.intruvia.rest.ingest.TokenBucket admission;
	public EventStreamServlet(Supplier<EventStreamComponent> streams, li.intruvia.rest.ingest.TokenBucket admission) {
		this.streams = streams;
		this.admission = admission;
	}

	@Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
		response.setHeader("Cache-Control", "no-store");
		int retry = this.admission.acquire();
		if (retry > 0) {
			response.setHeader("Retry-After", Integer.toString(retry));
			error(response, 429);
			return;
		}
		var stream = this.streams.get();
		var headers = new HashMap<String, List<String>>();
		for (String name : Collections.list(request.getHeaderNames()))
			headers.put(name.toLowerCase(Locale.ROOT), Collections.list(request.getHeaders(name)));
		try { stream.viewers().validateUpgrade(headers); }
		catch (ViewerException e) { error(response, e.getResponse().getStatus()); return; }
		long after;
		try {
			var query = request.getParameterMap();
			if (!query.keySet().equals(Set.of("after")) || query.get("after").length != 1) throw new IllegalArgumentException();
			String value = query.get("after")[0];
			if (value.length() > 19 || !value.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException();
			after = Long.parseLong(value);
		} catch (IllegalArgumentException e) { error(response, 400); return; }
		String token = ViewerSessions.cookieValues(headers.get("cookie"));
		var endpoint = new StreamEndpoint(stream, token, after);
		var config = ServerEndpointConfig.Builder.create(StreamEndpoint.class, "/api/v1/events/stream")
				.configurator(new ServerEndpointConfig.Configurator() {
					@Override public <T> T getEndpointInstance(Class<T> type) { return type.cast(endpoint); }
					@Override public boolean checkOrigin(String origin) { return stream.viewers().configuration().origin().equals(origin); }
				}).build();
		try {
			var container = (ServerContainer) getServletContext().getAttribute(ServerContainer.class.getName());
			container.upgradeHttpToWebSocket(request, response, config, Map.of());
		} catch (DeploymentException e) {
			if (!response.isCommitted()) error(response, 503);
		} catch (IllegalStateException e) {
			if (!response.isCommitted()) error(response, 400);
		}
	}

	private static void error(HttpServletResponse response, int status) throws IOException {
		response.setStatus(status);
		response.setContentType("application/json");
		response.getWriter().write(EventJson.write(new ApiError(new ApiError.Error(
				status == 400 ? "invalid_request" : "viewer_request_denied", "Stream request rejected", li.intruvia.rest.ops.RequestIds.current()))));
	}

	public static final class StreamEndpoint extends Endpoint {
		private final EventStreamComponent stream;
		private final String token;
		private final long after;
		private StreamConnection connection;
		StreamEndpoint(EventStreamComponent stream, String token, long after) {
			this.stream = stream; this.token = token; this.after = after;
		}
		@Override public void onOpen(Session session, EndpointConfig config) {
			this.connection = this.stream.connect(session, this.token, this.after);
			session.addMessageHandler(String.class, (MessageHandler.Whole<String>) message -> this.connection.close(1008, "No client commands"));
			session.addMessageHandler(ByteBuffer.class,
					(MessageHandler.Whole<ByteBuffer>) message -> this.connection.close(1008, "No client commands"));
			session.addMessageHandler(PongMessage.class, (MessageHandler.Whole<PongMessage>) message -> this.connection.pong());
		}
		@Override public void onClose(Session session, CloseReason reason) {
			if (this.connection != null) this.connection.close(1000, "Closed");
		}
		@Override public void onError(Session session, Throwable error) {
			if (this.connection != null) this.connection.close(1011, "Transport failed");
		}
	}
}
