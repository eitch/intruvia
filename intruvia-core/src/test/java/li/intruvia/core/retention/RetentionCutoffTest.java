// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.retention;

import java.time.Instant;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class RetentionCutoffTest {
	@Test
	public void canonicalUtcDateFastPathMatchesExactInstantOrdering() {
		var timestamps = List.of("2025-12-31T23:59:59Z", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00.000000001Z",
				"2026-01-01T00:00:00.999999999Z", "2026-01-01T23:59:59Z", "2026-01-02T00:00:00Z",
				"2028-02-29T23:59:59Z", "2028-03-01T00:00:00Z", "+10000-01-01T00:00:00Z", "-0001-01-01T00:00:00Z");
		for (String cutoffText : timestamps) {
			var cutoff = Instant.parse(cutoffText);
			for (String timestamp : timestamps)
				assertEquals(timestamp + " versus " + cutoffText, !Instant.parse(timestamp).isAfter(cutoff),
						RetentionCommand.expired(timestamp, cutoff, cutoffText));
		}
	}
}
