// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.geo;

import java.net.InetAddress;
import org.junit.Test;
import static org.junit.Assert.*;

public class PublicAddressPolicyTest {
	@Test
	public void excludesFirstAndLastAddressesOfSpecialRanges() {
		for (String ip : new String[]{"0.0.0.0", "0.255.255.255", "10.0.0.0", "10.255.255.255",
				"100.64.0.0", "100.127.255.255", "127.0.0.0", "127.255.255.255", "169.254.0.0", "169.254.255.255",
				"172.16.0.0", "172.31.255.255", "192.0.0.0", "192.0.0.255", "192.0.2.0", "192.0.2.255",
				"192.88.99.0", "192.88.99.255", "192.168.0.0", "192.168.255.255", "198.18.0.0", "198.19.255.255",
				"198.51.100.0", "198.51.100.255", "203.0.113.0", "203.0.113.255", "224.0.0.0", "255.255.255.255",
				"2001::", "2001:1ff:ffff:ffff:ffff:ffff:ffff:ffff", "2001:db8::", "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff",
				"2002::", "2002:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "3fff::", "3fff:fff:ffff:ffff:ffff:ffff:ffff:ffff",
				"1fff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "4000::", "fec0::", "100:0:0:1::"})
			assertFalse(ip, PublicAddressPolicy.isPublic(InetAddress.ofLiteral(ip)));
	}

	@Test
	public void allowsAdjacentOrdinaryUnicastAndPublicIpv4MappedAddresses() {
		for (String ip : new String[]{"1.0.0.0", "9.255.255.255", "11.0.0.0", "100.63.255.255", "100.128.0.0",
				"126.255.255.255", "128.0.0.0", "169.253.255.255", "169.255.0.0", "172.15.255.255", "172.32.0.0",
				"191.255.255.255", "192.0.1.0", "192.0.1.255", "192.0.3.0", "192.88.98.255", "192.88.100.0",
				"192.167.255.255", "192.169.0.0", "198.17.255.255", "198.20.0.0", "198.51.99.255", "198.51.101.0",
				"203.0.112.255", "203.0.114.0", "223.255.255.255", "::ffff:8.8.8.8", "2000::",
				"2000:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "2001:200::", "2001:db7:ffff:ffff:ffff:ffff:ffff:ffff", "2001:db9::",
				"2001:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "2003::", "3ffe:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "3fff:1000::",
				"3fff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "2606:4700:4700::1111"})
			assertTrue(ip, PublicAddressPolicy.isPublic(InetAddress.ofLiteral(ip)));
	}
}
