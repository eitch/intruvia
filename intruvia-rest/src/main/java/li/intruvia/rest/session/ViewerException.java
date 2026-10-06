// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.session;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;

/** Only fixed, application-owned error responses pass through this mapper. */
public final class ViewerException extends WebApplicationException {
	ViewerException(Response response) { super(response); }

	public static final class Mapper implements ExceptionMapper<ViewerException> {
		@Override public Response toResponse(ViewerException exception) { return exception.getResponse(); }
	}
}
