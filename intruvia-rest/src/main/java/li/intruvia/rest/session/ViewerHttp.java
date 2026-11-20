// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.session;

import jakarta.ws.rs.core.Response;
import li.intruvia.rest.dto.ApiError;
import li.intruvia.rest.ingest.IngestHttp;

final class ViewerHttp {
	private ViewerHttp() {}

	static ViewerException failure(int status) {
		String code = switch (status) {
			case 400 -> "invalid_request";
			case 401 -> "authentication_required";
			case 403 -> "viewer_access_denied";
			case 404 -> "not_found";
			case 409 -> "conflict";
			case 413 -> "body_too_large";
			case 415 -> "unsupported_media_type";
			case 500, 503 -> "administration_unavailable";
			default -> "session_unavailable";
		};
		String message = switch (status) {
			case 401 -> "Viewer authentication required";
			case 403 -> "Viewer request denied";
			case 404 -> "Administration item not found";
			case 409 -> "Administration change conflicts with current state";
			case 500, 503 -> "Administration service unavailable";
			default -> "Invalid session request";
		};
		Response response = IngestHttp.json(status, new ApiError(new ApiError.Error(code, message, li.intruvia.rest.ops.RequestIds.current())));
		return new ViewerException(response);
	}
}
