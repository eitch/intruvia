// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import li.intruvia.core.auth.MachineIdentities;
import li.intruvia.core.ingest.*;
import li.intruvia.rest.auth.MachineAuthenticationFilter.Identity;
import li.intruvia.rest.auth.MachineIngest;
import li.intruvia.rest.dto.Fail2banResponse;
import li.strolch.service.api.ServiceHandler;

@Path("fail2ban/events")
@MachineIngest
public final class Fail2banResource {
	private final Supplier<ServiceHandler> services;
	private final MachineIdentities identities;
	private final IngestionLimits limits;
	private final Fail2banRequestParser parser;
	private final Map<String, TokenBucket> buckets;

	public Fail2banResource(Supplier<ServiceHandler> services, MachineIdentities identities, IngestionLimits limits) {
		this.services = services;
		this.identities = identities;
		this.limits = limits;
		this.parser = new Fail2banRequestParser(limits.bodyBytes());
		this.buckets = identities.instances().values().stream().collect(Collectors.toUnmodifiableMap(value -> value,
				value -> new TokenBucket(limits.eventsPerSecond(), limits.burst())));
	}

	@POST
	public Response ingest(InputStream input, @Context SecurityContext security, @Context HttpHeaders headers) {
		Identity identity = (Identity) security.getUserPrincipal();
		int retry = this.buckets.get(identity.instanceId()).acquire();
		if (retry > 0)
			return IngestHttp.limited(retry);
		var media = headers.getMediaType();
		if (media == null || !media.getType().equalsIgnoreCase("application") || !media.getSubtype().equalsIgnoreCase("json") ||
				!media.getParameters().getOrDefault("charset", "UTF-8").equalsIgnoreCase("UTF-8") ||
				headers.getHeaderString("Content-Encoding") != null)
			return IngestHttp.error(415, "unsupported_media_type", "UTF-8 application/json required");
		try {
			var event = this.parser.parse(input);
			var result = this.services.get().doService(identity.certificate(),
					new IngestService(this.identities, Clock.systemUTC(), this.limits.agePolicy(), null), new IngestArgument(event));
			if (result.getState() == li.strolch.service.api.ServiceResultState.ACCESS_DENIED)
				return IngestHttp.error(403, "forbidden", "Ingestion permission required");
			return switch (result.outcome()) {
				case CREATED -> {
					var value = result.accepted();
					yield IngestHttp.json(201, new Fail2banResponse(value.id(), value.sequence(), false, value.geoStatus()));
				}
				case DUPLICATE -> {
					var value = result.accepted();
					yield IngestHttp.json(200, new DuplicateResponse(value.id(), value.sequence(), true));
				}
				case CONFLICT -> IngestHttp.error(409, "event_conflict", "Event ID already used with different payload");
				case INVALID -> IngestHttp.error(400, "invalid_event", "Invalid event payload");
				case FORBIDDEN -> IngestHttp.error(403, "forbidden", "Ingestion permission and server identity required");
				case UNAVAILABLE -> IngestHttp.error(503, "unavailable", "Ingestion unavailable");
			};
		} catch (InvalidEventException e) {
			return IngestHttp.error(e.isOversized() ? 413 : 400, e.isOversized() ? "body_too_large" : "invalid_event", e.getMessage());
		} catch (IOException e) {
			return IngestHttp.error(400, "invalid_event", "Cannot read event body");
		}
	}

	private record DuplicateResponse(UUID id, String sequence, boolean duplicate) {}
}
