// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.dto;

/** Input shape only; use Fail2banRequestParser at the ingestion boundary, not the generic JSON codec. */
public record Fail2banRequest(int schemaVersion, String eventId, String occurredAt, String action,
		String ip, String jail, Integer failures) {}
