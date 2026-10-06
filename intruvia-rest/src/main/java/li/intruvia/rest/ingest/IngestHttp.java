// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.ingest;

import java.util.UUID;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import li.intruvia.core.model.EventJson;
import li.intruvia.rest.dto.ApiError;

public final class IngestHttp {
	private IngestHttp() {}

	public static Response json(int status, Object value) {
		return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).header("Cache-Control", "no-store")
				.entity(EventJson.write(value)).build();
	}

	public static Response error(int status, String code, String message) {
		var response = Response.fromResponse(json(status, new ApiError(new ApiError.Error(code, message, UUID.randomUUID().toString()))));
		if (status == 401)
			response.header("WWW-Authenticate", "Bearer realm=\"intruvia-ingest\"");
		return response.build();
	}

	public static Response limited(int seconds) {
		return Response.fromResponse(error(429, "rate_limited", "Request limit exceeded")).header("Retry-After", seconds).build();
	}
}
