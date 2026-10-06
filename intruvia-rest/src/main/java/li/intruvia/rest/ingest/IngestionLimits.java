// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.Set;
import li.intruvia.core.ingest.EventAgePolicy;

/** External startup settings; finite bounds prevent accidental unbounded admission. */
public record IngestionLimits(int bodyBytes, int eventsPerSecond, int burst, int globalPerSecond, int globalBurst,
		int connections, int idleSeconds, EventAgePolicy agePolicy) {
	public static IngestionLimits load(Path file) throws IOException {
		Properties values = new Properties();
		if (Files.exists(file)) {
			try (var reader = Files.newBufferedReader(file)) { values.load(reader); }
		}
		Set<String> keys = Set.of("bodyBytes", "eventsPerSecond", "burst", "globalPerSecond", "globalBurst",
				"connections", "idleSeconds", "maximumAgeSeconds", "maximumFutureSeconds");
		if (!keys.containsAll(values.stringPropertyNames()))
			throw new IllegalArgumentException("Unknown ingestion limit setting");
		return new IngestionLimits(value(values, "bodyBytes", 16384, 1048576),
				value(values, "eventsPerSecond", 10, 100000), value(values, "burst", 100, 100000),
				value(values, "globalPerSecond", 100, 100000), value(values, "globalBurst", 1000, 100000),
				value(values, "connections", 256, 10000), value(values, "idleSeconds", 30, 300),
				new EventAgePolicy(Duration.ofSeconds(value(values, "maximumAgeSeconds", 604800, 2592000)),
						Duration.ofSeconds(value(values, "maximumFutureSeconds", 300, 86400))));
	}

	private static int value(Properties values, String key, int fallback, int maximum) {
		try {
			int value = Integer.parseInt(values.getProperty(key, Integer.toString(fallback)));
			if (value > 0 && value <= maximum)
				return value;
		} catch (NumberFormatException e) {
			// Do not echo external configuration values.
		}
		throw new IllegalArgumentException("Invalid ingestion limit: " + key);
	}
}
