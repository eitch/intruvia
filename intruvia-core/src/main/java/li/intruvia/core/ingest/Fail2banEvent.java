// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.ingest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import li.intruvia.core.model.SecurityEvent;

/** Validated producer data only: no identity, enrichment, received time or allocated sequence. */
public record Fail2banEvent(UUID eventId, Instant occurredAt, String ip, String jail, Integer failures) {
	public Fail2banEvent {
		Objects.requireNonNull(eventId);
		Objects.requireNonNull(occurredAt);
		ip = IpLiteral.normalize(ip);
		if (jail == null || jail.isEmpty() || jail.codePointCount(0, jail.length()) > 128 ||
				jail.codePoints().anyMatch(value -> Character.isISOControl(value) || value >= 0xd800 && value <= 0xdfff) ||
				failures != null && failures < 0)
			throw new InvalidEventException(false);
	}

	public static Fail2banEvent normalize(int schemaVersion, String eventId, String occurredAt, String action,
			String ip, String jail, Integer failures) {
		if (schemaVersion != 1 || !"ban".equals(action) || eventId == null ||
				!eventId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") ||
				occurredAt == null || !occurredAt.matches(
				"[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?([Zz]|[+-][0-9]{2}:[0-9]{2})"))
			throw new InvalidEventException(false);
		try {
			return new Fail2banEvent(UUID.fromString(eventId), OffsetDateTime.parse(occurredAt).toInstant(), ip, jail, failures);
		} catch (DateTimeParseException e) {
			throw new InvalidEventException(false);
		}
	}

	/** SHA-256 over versioned, length-prefixed UTF-8 fields; absent failures differ from zero. */
	public String payloadDigest() {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (String field : new String[]{"intruvia.fail2ban.v1", this.eventId.toString(), this.occurredAt.toString(),
					"ban", this.ip, this.jail, this.failures == null ? null : this.failures.toString()}) {
				byte[] bytes = field == null ? new byte[0] : field.getBytes(StandardCharsets.UTF_8);
				digest.update(ByteBuffer.allocate(4).putInt(field == null ? -1 : bytes.length).array());
				digest.update(bytes);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Required SHA-256 algorithm unavailable", e);
		}
	}

	/** The caller must supply the authenticated mapping and server-owned commit/enrichment values. */
	public SecurityEvent toEvent(String authenticatedInstanceId, UUID id, long sequence, Instant receivedAt, SecurityEvent.Geo geo) {
		Objects.requireNonNull(authenticatedInstanceId);
		return new SecurityEvent(1, id, Long.toString(sequence),
				new SecurityEvent.Source("fail2ban", authenticatedInstanceId, this.eventId), "network.security", "ban",
				this.occurredAt.toString(), receivedAt.toString(), new SecurityEvent.Subject(this.ip),
				new SecurityEvent.Observer(authenticatedInstanceId),
				new SecurityEvent.Attributes(new SecurityEvent.Fail2ban(this.jail, this.failures)), geo);
	}
}
