// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.retention;

import java.time.Duration;
import java.util.Set;
import li.strolch.runtime.configuration.ComponentConfiguration;

/** Trusted external settings. Receipts may never expire before the specified retry guarantee. */
public record RetentionPolicy(Duration eventAge, int eventCapacity, Duration receiptAge, int receiptCapacity, long sweepSeconds) {
	public static final RetentionPolicy DEFAULT = new RetentionPolicy(Duration.ofDays(30), 100000, Duration.ofDays(30), 1000000, 86400);

	public RetentionPolicy {
		if (eventAge == null || eventAge.isNegative() || eventAge.isZero() || eventAge.compareTo(Duration.ofDays(3650)) > 0 ||
				eventCapacity < 1 || eventCapacity > 1000000 || receiptAge == null || receiptAge.compareTo(Duration.ofDays(30)) < 0 ||
				receiptAge.compareTo(Duration.ofDays(3650)) > 0 || receiptCapacity < 1 || receiptCapacity > 10000000 ||
				sweepSeconds < 1 || sweepSeconds > 86400)
			throw new IllegalArgumentException("Invalid retention bounds");
	}

	static RetentionPolicy load(ComponentConfiguration config) {
		if (!Set.of("eventAgeSeconds", "eventCapacity", "receiptAgeSeconds", "receiptCapacity", "sweepSeconds")
				.containsAll(config.getPropertyKeys()))
			throw new IllegalArgumentException("Unknown retention setting");
		return new RetentionPolicy(Duration.ofSeconds(number(config, "eventAgeSeconds", "2592000")),
				Integer.parseInt(Long.toString(number(config, "eventCapacity", "100000"))),
				Duration.ofSeconds(number(config, "receiptAgeSeconds", "2592000")),
				Integer.parseInt(Long.toString(number(config, "receiptCapacity", "1000000"))),
				number(config, "sweepSeconds", "86400"));
	}
	private static long number(ComponentConfiguration config, String key, String fallback) {
		String value = config.getAsMap().getOrDefault(key, fallback);
		if (!value.matches("[1-9][0-9]{0,9}"))
			throw new IllegalArgumentException("Invalid retention setting");
		return Long.parseLong(value);
	}
}
