// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.ingest;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Apply only after looking up the validated payload's receipt. Boundaries are inclusive. */
public record EventAgePolicy(Duration maximumAge, Duration maximumFutureSkew) {
	public static final EventAgePolicy DEFAULT = new EventAgePolicy(Duration.ofDays(7), Duration.ofMinutes(5));

	public EventAgePolicy {
		Objects.requireNonNull(maximumAge);
		Objects.requireNonNull(maximumFutureSkew);
		if (maximumAge.isNegative() || maximumFutureSkew.isNegative())
			throw new IllegalArgumentException("Event time limits must be nonnegative");
	}

	public void validate(Instant occurredAt, Instant now) {
		Duration age = Duration.between(occurredAt, now);
		if (age.compareTo(this.maximumAge) > 0 || age.compareTo(this.maximumFutureSkew.negated()) < 0)
			throw new InvalidEventException(false);
	}
}
