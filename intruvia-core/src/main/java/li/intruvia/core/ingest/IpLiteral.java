// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.ingest;

import java.net.InetAddress;

/** Strict literals only. InetAddress.ofLiteral never invokes a name service. */
public final class IpLiteral {
	private IpLiteral() {}

	public static String normalize(String value) {
		if (value == null || value.isEmpty() || value.length() > 45 || !value.matches("[0-9a-fA-F:.]+"))
			throw new InvalidEventException(false);
		if (!value.contains(":"))
			validateIpv4(value);
		else if (value.contains("."))
			validateIpv4(value.substring(value.lastIndexOf(':') + 1));
		try {
			byte[] bytes = InetAddress.ofLiteral(value).getAddress();
			if (bytes.length == 4)
				return (bytes[0] & 255) + "." + (bytes[1] & 255) + "." + (bytes[2] & 255) + "." + (bytes[3] & 255);
			return formatIpv6(bytes);
		} catch (IllegalArgumentException e) {
			throw new InvalidEventException(false);
		}
	}

	private static void validateIpv4(String value) {
		String[] parts = value.split("\\.", -1);
		if (parts.length != 4)
			throw new InvalidEventException(false);
		for (String part : parts) {
			if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255)
				throw new InvalidEventException(false);
		}
	}

	private static String formatIpv6(byte[] bytes) {
		int[] words = new int[8];
		int bestStart = -1;
		int bestLength = 1;
		for (int i = 0; i < words.length; i++)
			words[i] = (bytes[i * 2] & 255) << 8 | (bytes[i * 2 + 1] & 255);
		for (int i = 0; i < words.length;) {
			if (words[i] != 0) {
				i++;
				continue;
			}
			int start = i;
			while (i < words.length && words[i] == 0)
				i++;
			if (i - start > bestLength) {
				bestStart = start;
				bestLength = i - start;
			}
		}
		StringBuilder result = new StringBuilder();
		for (int i = 0; i < words.length; i++) {
			if (i == bestStart) {
				result.append("::");
				i += bestLength - 1;
			} else {
				if (!result.isEmpty() && result.charAt(result.length() - 1) != ':')
					result.append(':');
				result.append(Integer.toHexString(words[i]));
			}
		}
		return result.toString();
	}
}
