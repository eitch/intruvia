// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.geo;

import java.net.InetAddress;
import java.util.List;

/** Conservative geographic unicast policy; see docs/architecture/007-geoip-enrichment.md. */
final class PublicAddressPolicy {
	private static final List<Prefix> IPV4_EXCLUDED = prefixes("0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
			"169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16",
			"198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/3");
	private static final List<Prefix> IPV6_EXCLUDED = prefixes("2001::/23", "2001:db8::/32", "2002::/16", "3fff::/20");
	private static final Prefix IPV6_UNICAST = Prefix.parse("2000::/3");

	private PublicAddressPolicy() {}

	static boolean isPublic(InetAddress address) {
		byte[] bytes = address.getAddress();
		if (bytes.length == 4)
			return IPV4_EXCLUDED.stream().noneMatch(prefix -> prefix.contains(bytes));
		return IPV6_UNICAST.contains(bytes) && IPV6_EXCLUDED.stream().noneMatch(prefix -> prefix.contains(bytes));
	}

	private static List<Prefix> prefixes(String... values) {
		return java.util.Arrays.stream(values).map(Prefix::parse).toList();
	}

	private record Prefix(byte[] address, int bits) {
		static Prefix parse(String value) {
			String[] parts = value.split("/");
			return new Prefix(InetAddress.ofLiteral(parts[0]).getAddress(), Integer.parseInt(parts[1]));
		}

		boolean contains(byte[] candidate) {
			if (candidate.length != address.length)
				return false;
			for (int i = 0; i < bits; i++) {
				int mask = 1 << (7 - i % 8);
				if ((candidate[i / 8] & mask) != (address[i / 8] & mask))
					return false;
			}
			return true;
		}
	}
}
