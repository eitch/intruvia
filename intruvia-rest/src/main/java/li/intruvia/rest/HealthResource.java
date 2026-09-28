// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/live")
public class HealthResource {
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public String live() {
		return "{\"status\":\"UP\"}";
	}
}
