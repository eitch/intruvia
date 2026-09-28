// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.dto;

/** Input shape only; authentication, strict parsing and normalization are task 006/010. */
public record Fail2banRequest(int schemaVersion, String eventId, String occurredAt, String action,
		String ip, String jail, Integer failures) {}
