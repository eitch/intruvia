// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.retention;

import java.io.File;
import java.util.Map;
import java.util.Set;
import li.strolch.runtime.configuration.ComponentConfiguration;
import li.strolch.runtime.configuration.RuntimeConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;

public class RetentionPolicyTest {
	@Test public void defaultsAndConfiguredBounds() {
		assertEquals(RetentionPolicy.DEFAULT, load(Map.of()));
		assertEquals(1, load(Map.of("eventCapacity", "1", "receiptCapacity", "1", "sweepSeconds", "1")).eventCapacity());
		assertEquals(2592000, RetentionPolicy.DEFAULT.receiptAge().getSeconds());
	}
	@Test public void unsafeMalformedAndUnknownSettingsFail() {
		for (var entry : Map.of("receiptAgeSeconds", "2591999", "eventCapacity", "1000001", "receiptCapacity", "10000001",
				"eventAgeSeconds", "315360001", "sweepSeconds", "86401", "typo", "1").entrySet())
			assertThrows(IllegalArgumentException.class, () -> load(Map.of(entry.getKey(), entry.getValue())));
		for (String value : new String[]{"", "0", "-1", "+1", "01", "1.5", " 1", "2147483648", "999999999999999999999"})
			assertThrows(IllegalArgumentException.class, () -> load(Map.of("eventCapacity", value)));
	}
	private static RetentionPolicy load(Map<String, String> values) {
		return RetentionPolicy.load(new ComponentConfiguration(new RuntimeConfiguration("test", "test", Map.of(),
				new File("target"), new File("target"), new File("target"), Set.of()), "Retention",
				values, RetentionComponent.class.getName(), RetentionComponent.class.getName(), Set.of()));
	}
}
