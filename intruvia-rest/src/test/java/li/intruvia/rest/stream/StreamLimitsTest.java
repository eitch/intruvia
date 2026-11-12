// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.stream;

import org.junit.Test;
import static org.junit.Assert.*;

public class StreamLimitsTest {
	@Test public void finiteBoundsRejectUnboundedAndUndeliverableSettings() {
		assertEquals(1000, StreamLimits.DEFAULT.queueFrames());
		for (int queue : new int[]{0, -1, 1001})
			assertThrows(IllegalArgumentException.class, () -> new StreamLimits(queue, 65536, 10, 250));
		for (int bytes : new int[]{0, 1023, 65537})
			assertThrows(IllegalArgumentException.class, () -> new StreamLimits(1000, bytes, 10, 250));
		assertThrows(IllegalArgumentException.class, () -> new StreamLimits(1000, 65536, 31, 250));
		assertThrows(IllegalArgumentException.class, () -> new StreamLimits(1000, 65536, 10, 0));
	}
	@Test public void configurationRejectsUnknownKeysAndHidesMalformedValues() {
		var root = new java.io.File("target");
		var runtime = new li.strolch.runtime.configuration.RuntimeConfiguration("Intruvia", "test", java.util.Map.of(),
				root, root, root, java.util.Set.of());
		for (var values : java.util.List.of(java.util.Map.of("queueFrames", "CANARY"), java.util.Map.of("typo", "100"))) {
			var config = new li.strolch.runtime.configuration.ComponentConfiguration(runtime, "EventStream", values,
					EventStreamComponent.class.getName(), EventStreamComponent.class.getName(), java.util.Set.of());
			String message = assertThrows(IllegalArgumentException.class, () -> StreamLimits.load(config)).getMessage();
			assertFalse(message.contains("CANARY"));
		}
	}
}
