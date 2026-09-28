// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.dto;

public record ApiError(Error error) {
	public record Error(String code, String message, String requestId) {}
}
