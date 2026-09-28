// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.model;

import java.util.Objects;
import java.util.UUID;

public record SecurityEvent(int schemaVersion, UUID id, String sequence, Source source, String category, String action,
		String occurredAt, String receivedAt, Subject subject, Observer observer, Attributes attributes, Geo geo) {
	public SecurityEvent {
		ContractValues.version(schemaVersion);
		Objects.requireNonNull(id);
		sequence = ContractValues.sequence(sequence);
		if (sequence.equals("0"))
			throw new IllegalArgumentException("Event sequence must be positive");
		Objects.requireNonNull(source);
		Objects.requireNonNull(category);
		Objects.requireNonNull(action);
		occurredAt = ContractValues.timestamp(Objects.requireNonNull(occurredAt));
		receivedAt = ContractValues.timestamp(Objects.requireNonNull(receivedAt));
		Objects.requireNonNull(subject);
		Objects.requireNonNull(observer);
		Objects.requireNonNull(attributes);
		Objects.requireNonNull(geo);
	}

	public record Source(String type, String instanceId, UUID eventId) {
		public Source {
			Objects.requireNonNull(type);
			Objects.requireNonNull(instanceId);
			Objects.requireNonNull(eventId);
		}
	}

	public record Subject(String ip) {
		public Subject { Objects.requireNonNull(ip); }
	}

	public record Observer(String id) {
		public Observer { Objects.requireNonNull(id); }
	}

	/** Closed v1 namespace; no arbitrary inbound JSON is retained. */
	public record Attributes(Fail2ban fail2ban) {
		public Attributes { Objects.requireNonNull(fail2ban); }
	}

	public record Fail2ban(String jail, Integer failures) {
		public Fail2ban {
			Objects.requireNonNull(jail);
			if (jail.isEmpty() || jail.length() > 128 || jail.codePoints().anyMatch(Character::isISOControl))
				throw new IllegalArgumentException("Invalid jail");
			if (failures != null && failures < 0)
				throw new IllegalArgumentException("Negative failures");
		}
	}

	public record Geo(GeoStatus status, String countryCode, String countryName, String region, String city,
			Double latitude, Double longitude, Double accuracyRadiusKm, String provider, String databaseEdition,
			String databaseBuildAt, String lookedUpAt) {
		public Geo {
			Objects.requireNonNull(status);
			if ((latitude == null) != (longitude == null))
				throw new IllegalArgumentException("Coordinates must be present or absent together");
			if (latitude != null && (!Double.isFinite(latitude) || latitude < -90 || latitude > 90 ||
					!Double.isFinite(longitude) || longitude < -180 || longitude > 180))
				throw new IllegalArgumentException("Invalid coordinates");
			if (accuracyRadiusKm != null && (!Double.isFinite(accuracyRadiusKm) || accuracyRadiusKm < 0))
				throw new IllegalArgumentException("Invalid accuracy radius");
			databaseBuildAt = ContractValues.timestamp(databaseBuildAt);
			lookedUpAt = ContractValues.timestamp(lookedUpAt);
		}
	}
}
