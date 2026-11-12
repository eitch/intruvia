// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class BindAddressTest {
	@Test
	public void defaultsToLoopbackAndAcceptsNumericAddresses() {
		assertEquals("127.0.0.1", BindAddress.parse(null));
		assertEquals("0.0.0.0", BindAddress.parse("0.0.0.0"));
		assertEquals("192.0.2.42", BindAddress.parse("192.0.2.42"));
		assertEquals("::1", BindAddress.parse("::1"));
		assertEquals("2001:db8::1", BindAddress.parse("2001:db8::1"));
		assertEquals("::ffff:192.0.2.1", BindAddress.parse("::ffff:192.0.2.1"));
	}

	@Test
	public void rejectsBlankHostnamesAndMalformedAddresses() {
		for (String address : new String[]{"", " ", "intruvia", "localhost", "256.0.0.1", "127.1", "010.0.0.1",
				"2001:::1", "fe80::1%eth0"})
			assertThrows(IllegalArgumentException.class, () -> BindAddress.parse(address));
	}
}