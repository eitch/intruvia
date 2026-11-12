// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import li.intruvia.rest.ingest.IngestHttp;
import java.util.Map;
import java.util.function.BooleanSupplier;

@Path("/")
public final class HealthResource {
	private final BooleanSupplier readiness;
	public HealthResource() { this(() -> false); }
	public HealthResource(BooleanSupplier readiness) { this.readiness = readiness; }
	@GET @Path("live") public Response live() { return IngestHttp.json(200, Map.of("status", "UP")); }
	@GET @Path("ready") public Response ready() {
		boolean ready = this.readiness.getAsBoolean();
		return IngestHttp.json(ready ? 200 : 503, Map.of("status", ready ? "UP" : "DOWN"));
	}
}
