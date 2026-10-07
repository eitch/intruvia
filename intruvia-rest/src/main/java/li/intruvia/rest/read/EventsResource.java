// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.read;

import java.util.Set;
import java.util.function.Supplier;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import li.intruvia.core.read.*;
import li.intruvia.rest.ingest.IngestHttp;
import li.intruvia.rest.session.ViewerAuthenticationFilter.Identity;
import li.intruvia.rest.session.ViewerRead;
import li.strolch.service.api.ServiceHandler;
import li.strolch.service.api.ServiceResultState;

@Path("events")
@ViewerRead
public final class EventsResource {
	private final Supplier<ServiceHandler> services;
	public EventsResource(Supplier<ServiceHandler> services) { this.services = services; }

	@GET
	@Path("snapshot")
	public Response snapshot(@Context UriInfo uri, @Context SecurityContext security) {
		return read(uri, security, true);
	}

	@GET
	public Response history(@Context UriInfo uri, @Context SecurityContext security) {
		return read(uri, security, false);
	}

	private Response read(UriInfo uri, SecurityContext security, boolean snapshot) {
		EventReadArgument argument;
		try {
			var query = uri.getQueryParameters();
			Set<String> allowed = snapshot ? Set.of("limit") : Set.of("after", "through", "limit");
			if (!allowed.containsAll(query.keySet()) || query.values().stream().anyMatch(values -> values.size() != 1))
				throw new IllegalArgumentException();
			int limit = query.containsKey("limit") ? Math.toIntExact(decimal(query.getFirst("limit"))) : snapshot ? 1000 : 500;
			if (limit < 1 || limit > 1000) throw new IllegalArgumentException();
			long after = snapshot ? 0 : decimal(query.getFirst("after"));
			Long through = query.containsKey("through") ? decimal(query.getFirst("through")) : null;
			argument = new EventReadArgument(snapshot, after, through, limit);
		} catch (IllegalArgumentException | ArithmeticException e) {
			return invalid();
		}
		var identity = (Identity) security.getUserPrincipal();
		var result = this.services.get().doService(identity.certificate(), new EventReadService(), argument);
		if (result.getState() == ServiceResultState.ACCESS_DENIED)
			return IngestHttp.error(403, "viewer_access_denied", "Event read permission required");
		return switch (result.outcome()) {
			case OK -> IngestHttp.json(200, result.body());
			case INVALID -> invalid();
			case EXPIRED -> IngestHttp.error(410, "resync_required", "Replay cursor expired; load a new snapshot");
			case UNAVAILABLE -> IngestHttp.error(503, "events_unavailable", "Event reads unavailable");
		};
	}

	private static long decimal(String value) {
		if (value == null || value.length() > 19 || !value.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException();
		return Long.parseLong(value);
	}
	private static Response invalid() { return IngestHttp.error(400, "invalid_request", "Invalid event limit or cursor"); }
}
