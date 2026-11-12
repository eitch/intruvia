// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.ops;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import li.intruvia.rest.ingest.IngestHttp;
import li.intruvia.rest.session.ViewerSessions;
import java.util.function.Supplier;

/** An operator must have both viewer access and the distinct runtime:diagnostics privilege. */
@Path("diagnostics")
public final class DiagnosticsResource {
	private final ViewerSessions sessions;
	private final Supplier<Object> snapshot;
	public DiagnosticsResource(ViewerSessions sessions, Supplier<Object> snapshot) {
		this.sessions = sessions;
		this.snapshot = snapshot;
	}
	@GET public Response get(@Context HttpHeaders headers) {
		this.sessions.requirePrivilege(this.sessions.validate(headers), "runtime:diagnostics");
		return IngestHttp.json(200, this.snapshot.get());
	}
}
