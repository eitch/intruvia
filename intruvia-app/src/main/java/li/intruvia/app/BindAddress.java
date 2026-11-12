// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import java.net.InetAddress;

final class BindAddress {
	private static final String DEFAULT = "127.0.0.1";

	private BindAddress() {}

	static String parse(String value) {
		if (value == null) return DEFAULT;
		if (value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) {
			for (String octet : value.split("\\."))
				if (Integer.parseInt(octet) > 255) throw invalid();
			return value;
		}
		if (value.indexOf(':') >= 0 && value.matches("[0-9A-Fa-f:.]+")) {
			try {
				InetAddress.getByName(value);
				return value;
			} catch (java.net.UnknownHostException e) {
				throw invalid();
			}
		}
		throw invalid();
	}

	private static ConfigurationFailure invalid() {
		return new ConfigurationFailure("Invalid INTRUVIA_BIND_ADDRESS; expected an IPv4 or IPv6 literal");
	}
}