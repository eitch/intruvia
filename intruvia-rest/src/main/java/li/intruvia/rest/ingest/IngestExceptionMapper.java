// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.ingest;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import li.strolch.exception.StrolchAccessDeniedException;

/** Last HTTP boundary: never serialize framework exception messages, payloads or credentials. */
public final class IngestExceptionMapper implements ExceptionMapper<Exception> {
	@Override
	public Response toResponse(Exception exception) {
		if (exception instanceof StrolchAccessDeniedException)
			return IngestHttp.error(403, "forbidden", "Ingestion permission required");
		if (exception instanceof WebApplicationException web) {
			int status = web.getResponse().getStatus();
			if (status >= 400 && status < 500)
				return IngestHttp.error(status, "invalid_request", "Request rejected");
		}
		return IngestHttp.error(503, "unavailable", "Ingestion unavailable");
	}
}
